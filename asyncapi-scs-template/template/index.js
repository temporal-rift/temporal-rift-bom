import { File, Text } from '@asyncapi/generator-react-sdk';

const JAVA_PACKAGE = 'io.github.temporalrift.asyncapi.generated';

function javaName(value) {
  return value.replace(/[^A-Za-z0-9]/g, '');
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
