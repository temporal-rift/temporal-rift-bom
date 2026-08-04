import { File, Text } from '@asyncapi/generator-react-sdk';

const JAVA_PACKAGE = 'io.github.temporalrift.asyncapi.generated';

function javaName(value) {
  const camel = value
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((part, index) => (index === 0 ? part : part.charAt(0).toUpperCase() + part.slice(1)))
    .join('');
  return /^[0-9]/.test(camel) ? `Msg${camel}` : camel;
}

function bindingName(address) {
  return `${address.replace(/[^A-Za-z0-9]+/g, '-')}-out`;
}

function javaType(schema) {
  if (!schema) {
    return 'Object';
  }
  const type = schema.type && schema.type();
  const format = schema.format && schema.format();
  switch (type) {
    case 'string':
      if (format === 'uuid') return 'UUID';
      if (format === 'date-time') return 'Instant';
      return 'String';
    case 'integer':
      return format === 'int64' ? 'long' : 'int';
    case 'number':
      return 'double';
    case 'boolean':
      return 'boolean';
    default:
      return 'Object';
  }
}

function payloadFields(message) {
  const payload = message.payload && message.payload();
  const properties = (payload && payload.properties && payload.properties()) || {};
  const fields = Object.entries(properties).map(
    ([propertyName, propertySchema]) => `${javaType(propertySchema)} ${javaName(propertyName)}`,
  );
  const seenFieldNames = new Map();
  for (const [propertyName] of Object.entries(properties)) {
    const fieldName = javaName(propertyName);
    const collidesWith = seenFieldNames.get(fieldName);
    if (collidesWith) {
      throw new Error(
        `Payload properties "${collidesWith}" and "${propertyName}" both generate the Java field "${fieldName}"`,
      );
    }
    seenFieldNames.set(fieldName, propertyName);
  }
  return fields.join(', ');
}

/**
 * Generates one contract surface per AsyncAPI document. Bindings derive only from channel addresses; operations
 * contribute typed methods but can never create a physical destination.
 */
export default function ({ asyncapi }) {
  const channels = asyncapi.channels().all();
  const messages = [...new Map(channels.flatMap((channel) => channel.messages().all())
    .map((message) => [message.name() ?? message.id(), message])).values()];
  const seenJavaNames = new Map();
  for (const message of messages) {
    const rawName = message.name() ?? message.id();
    const name = javaName(rawName);
    const collidesWith = seenJavaNames.get(name);
    if (collidesWith) {
      throw new Error(`Message names "${collidesWith}" and "${rawName}" both generate the Java identifier "${name}"`);
    }
    seenJavaNames.set(name, rawName);
  }

  const channel = channels[0];
  const address = channel.address();
  const producerMethods = messages.map((message) => {
    const name = javaName(message.name() ?? message.id());
    return `        boolean publish${name}(${name}Payload payload, EventHeaders headers);`;
  }).join('\n');
  const consumerMethods = messages.map((message) => {
    const name = javaName(message.name() ?? message.id());
    return `        void on${name}(${name}Payload payload, EventHeaders headers);`;
  }).join('\n');
  const payloads = messages.map((message) => {
    const name = javaName(message.name() ?? message.id());
    return `    public record ${name}Payload(${payloadFields(message)}) {}\n    public static final String ${name.replace(/[A-Z]/g, (letter) => `_${letter}`).toUpperCase().replace(/^_/, '')}_EVENT_TYPE = "${message.name() ?? message.id()}";`;
  }).join('\n\n');
  const dispatchCases = messages.map((message) => {
    const name = javaName(message.name() ?? message.id());
    const eventTypeConstant = `${name.replace(/[A-Z]/g, (letter) => `_${letter}`).toUpperCase().replace(/^_/, '')}_EVENT_TYPE`;
    return `            case ${eventTypeConstant} -> on${name}(deserializer.deserialize(rawPayload, ${name}Payload.class), headers);`;
  }).join('\n');

  return (
    <File name="GeneratedChannelContract.java">
      <Text>{`package ${JAVA_PACKAGE};

import java.time.Instant;
import java.util.UUID;

/** Generated from the AsyncAPI 3 channel and message contract. */
@javax.annotation.processing.Generated("@temporal-rift/asyncapi-spring-scs-template")
public final class GeneratedChannelContract {

    public static final String CHANNEL = "${address}";
    public static final String OUTPUT_BINDING = "${bindingName(address)}";

    private GeneratedChannelContract() {}

    public record EventHeaders(
            UUID eventId,
            UUID aggregateId,
            String aggregateType,
            UUID gameId,
            Instant occurredAt,
            int version) {}

${payloads}

    public interface Producer {
${producerMethods}
    }

    /** Converts a raw inbound payload (e.g. a parsed JSON tree) into a typed generated record. */
    public interface PayloadDeserializer {
        <T> T deserialize(Object rawPayload, Class<T> type);
    }

    public interface Consumer {
${consumerMethods}

        /**
         * Selects and invokes the typed handler for {@code eventType}, deserializing {@code rawPayload} into that
         * message's generated payload type. Callers read {@code eventType} from the inbound record's header.
         */
        default void dispatch(String eventType, Object rawPayload, EventHeaders headers, PayloadDeserializer deserializer) {
            switch (eventType) {
${dispatchCases}
                default -> throw new IllegalArgumentException("Unknown eventType: " + eventType);
            }
        }
    }
}
`}</Text>
    </File>
  );
}
