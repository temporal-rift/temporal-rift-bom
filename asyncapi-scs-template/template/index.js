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
    return `    public record ${name}Payload(String json) {}`;
  }).join('\n\n');

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

    public interface Consumer {
${consumerMethods}
    }
}
`}</Text>
    </File>
  );
}
