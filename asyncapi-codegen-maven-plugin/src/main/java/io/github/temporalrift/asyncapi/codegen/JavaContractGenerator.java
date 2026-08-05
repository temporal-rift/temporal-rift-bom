package io.github.temporalrift.asyncapi.codegen;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Generates a single Java contract source for one AsyncAPI channel: payload records (fields derived from each
 * message's JSON Schema payload), an {@code EVENT_TYPE} constant per message, {@code Producer}/{@code Consumer}
 * interfaces, and a {@code Consumer.dispatch} method routing a raw record to its typed handler by {@code eventType}.
 */
final class JavaContractGenerator {

    private static final Pattern NAME_SEPARATOR = Pattern.compile("[^A-Za-z0-9]+");

    private final AsyncApiDocument document;
    private final String javaPackage;
    private final String className;
    private final Map<String, String> nestedTypeSources = new LinkedHashMap<>();

    JavaContractGenerator(AsyncApiDocument document, String javaPackage, String className) {
        this.document = document;
        this.javaPackage = javaPackage;
        this.className = className;
    }

    String generate(AsyncApiDocument.Channel channel) {
        List<AsyncApiDocument.Message> messages = channel.messages();
        String address = channel.address();

        Map<String, String> javaNameByMessage = new LinkedHashMap<>();
        Map<String, String> seenJavaNames = new LinkedHashMap<>();
        for (AsyncApiDocument.Message message : messages) {
            String name = javaName(message.name());
            String collidesWith = seenJavaNames.put(name, message.name());
            if (collidesWith != null) {
                throw new IllegalStateException("Message names \"" + collidesWith + "\" and \"" + message.name()
                        + "\" both generate the Java identifier \"" + name + "\"");
            }
            javaNameByMessage.put(message.name(), name);
        }

        StringBuilder payloads = new StringBuilder();
        StringBuilder producerMethods = new StringBuilder();
        StringBuilder consumerMethods = new StringBuilder();
        StringBuilder dispatchCases = new StringBuilder();

        for (AsyncApiDocument.Message message : messages) {
            String name = javaNameByMessage.get(message.name());
            String eventTypeConstant = eventTypeConstantName(name);

            payloads.append("    public record ").append(name).append("Payload(")
                    .append(recordFields(message.payloadSchema(), document.specFile()))
                    .append(") {}\n");
            payloads.append("    public static final String ").append(eventTypeConstant).append(" = \"")
                    .append(message.name()).append("\";\n\n");

            producerMethods.append("        boolean publish").append(name).append('(').append(name)
                    .append("Payload payload, EventHeaders headers);\n");
            consumerMethods.append("        void on").append(name).append('(').append(name)
                    .append("Payload payload, EventHeaders headers);\n");
            dispatchCases.append("            case ").append(eventTypeConstant).append(" -> on").append(name)
                    .append("(deserializer.deserialize(rawPayload, ").append(name).append("Payload.class), headers);\n");
        }

        StringBuilder nestedTypesSource = new StringBuilder();
        for (String source : nestedTypeSources.values()) {
            nestedTypesSource.append(source).append('\n');
        }

        return """
                package %s;

                import java.time.Instant;
                import java.util.List;
                import java.util.UUID;

                /** Generated from the AsyncAPI 3 channel and message contract. */
                public final class %s {

                    public static final String CHANNEL = "%s";
                    public static final String OUTPUT_BINDING = "%s";

                    private %s() {}

                    public record EventHeaders(
                            UUID eventId,
                            UUID aggregateId,
                            String aggregateType,
                            UUID gameId,
                            Instant occurredAt,
                            int version) {}

                %s
                %s
                    public interface Producer {
                %s    }

                    public interface PayloadDeserializer {
                        <T> T deserialize(Object rawPayload, Class<T> type);
                    }

                    public interface Consumer {
                %s
                        default void dispatch(String eventType, Object rawPayload, EventHeaders headers, PayloadDeserializer deserializer) {
                            switch (eventType) {
                %s            default -> throw new IllegalArgumentException("Unknown eventType: " + eventType);
                            }
                        }
                    }
                }
                """
                .formatted(
                        javaPackage,
                        className,
                        address,
                        bindingName(address),
                        className,
                        payloads,
                        nestedTypesSource,
                        producerMethods,
                        consumerMethods,
                        dispatchCases);
    }

    private String recordFields(JsonNode objectSchema, Path specFile) {
        JsonNode resolved = document.resolve(objectSchema, specFile);
        JsonNode properties = resolved.path("properties");
        Map<String, String> seenFieldNames = new LinkedHashMap<>();
        StringBuilder fields = new StringBuilder();
        var fieldNames = properties.fieldNames();
        boolean first = true;
        while (fieldNames.hasNext()) {
            String propertyName = fieldNames.next();
            String fieldName = javaName(propertyName);
            String collidesWith = seenFieldNames.put(fieldName, propertyName);
            if (collidesWith != null) {
                throw new IllegalStateException("Payload properties \"" + collidesWith + "\" and \"" + propertyName
                        + "\" both generate the Java field \"" + fieldName + "\"");
            }
            if (!first) {
                fields.append(", ");
            }
            first = false;
            fields.append(javaType(properties.path(propertyName), specFile, propertyName)).append(' ').append(fieldName);
        }
        return fields.toString();
    }

    private String javaType(JsonNode propertySchema, Path specFile, String contextName) {
        String refName = null;
        if (propertySchema.has("$ref")) {
            String ref = propertySchema.path("$ref").asText();
            String fragment = ref.contains("#") ? ref.substring(ref.indexOf('#') + 1) : ref;
            int lastSlash = fragment.lastIndexOf('/');
            refName = lastSlash >= 0 ? fragment.substring(lastSlash + 1) : fragment;
        }
        JsonNode schema = document.resolve(propertySchema, specFile);
        String type = schema.path("type").asText();
        String format = schema.path("format").asText(null);

        return switch (type) {
            case "string" -> {
                if (schema.has("enum")) {
                    yield registerEnum(refName != null ? refName : capitalize(contextName), schema);
                }
                if ("uuid".equals(format)) {
                    yield "UUID";
                }
                if ("date-time".equals(format)) {
                    yield "Instant";
                }
                yield "String";
            }
            case "integer" -> "int64".equals(format) ? "long" : "int";
            case "number" -> "double";
            case "boolean" -> "boolean";
            case "array" -> {
                String itemsType = javaType(schema.path("items"), specFile, contextName + "Item");
                yield "List<" + itemsType + ">";
            }
            case "object" -> registerRecord(refName != null ? refName : capitalize(contextName), schema, specFile);
            default -> "Object";
        };
    }

    private String registerEnum(String name, JsonNode schema) {
        if (!nestedTypeSources.containsKey(name)) {
            StringBuilder constants = new StringBuilder();
            boolean first = true;
            for (JsonNode value : schema.path("enum")) {
                if (!first) {
                    constants.append(", ");
                }
                first = false;
                constants.append(value.asText());
            }
            nestedTypeSources.put(name, "    public enum " + name + " { " + constants + " }");
        }
        return name;
    }

    private String registerRecord(String name, JsonNode schema, Path specFile) {
        if (!nestedTypeSources.containsKey(name)) {
            nestedTypeSources.put(name, "PLACEHOLDER");
            String fields = recordFields(schema, specFile);
            nestedTypeSources.put(name, "    public record " + name + "(" + fields + ") {}");
        }
        return name;
    }

    static String javaName(String value) {
        String[] parts = NAME_SEPARATOR.split(value);
        StringBuilder camel = new StringBuilder();
        boolean first = true;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (first) {
                camel.append(part);
                first = false;
            } else {
                camel.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        String result = camel.toString();
        return Character.isDigit(result.charAt(0)) ? "Msg" + result : result;
    }

    private static String capitalize(String value) {
        String name = javaName(value);
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String eventTypeConstantName(String javaName) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < javaName.length(); i++) {
            char c = javaName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(c));
        }
        return result + "_EVENT_TYPE";
    }

    private static String bindingName(String address) {
        return address.replaceAll("[^A-Za-z0-9]+", "-") + "-out";
    }
}
