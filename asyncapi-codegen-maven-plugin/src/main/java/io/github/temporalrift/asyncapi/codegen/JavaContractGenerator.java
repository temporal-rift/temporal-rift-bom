package io.github.temporalrift.asyncapi.codegen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.SourceVersion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Generates a single Java contract source for one AsyncAPI channel: payload records (fields derived from each
 * message's JSON Schema payload), an {@code EVENT_TYPE} constant per message, {@code Producer}/{@code Consumer}
 * interfaces, and a {@code Consumer.dispatch} method routing a raw record to its typed handler by {@code eventType}.
 */
final class JavaContractGenerator {

    private static final Pattern NAME_SEPARATOR = Pattern.compile("[^A-Za-z0-9]+");
    private static final String BOOLEAN_TYPE = "boolean";
    private static final String PROPERTIES = "properties";
    private static final String ONE_OF = "oneOf";
    /** Placeholder claimName() schema for fixed member type names — never equal to a real parsed schema node. */
    private static final JsonNode FIXED_TYPE_MARKER = MissingNode.getInstance();

    private static final Set<String> RESERVED_WORDS = Set.of(
            "abstract",
            "assert",
            BOOLEAN_TYPE,
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "_");

    private final AsyncApiDocument document;
    private final String javaPackage;
    private final String className;
    private final Map<String, String> nestedTypeSources = new LinkedHashMap<>();
    private final Map<String, JsonNode> nestedTypeSchemas = new LinkedHashMap<>();

    JavaContractGenerator(AsyncApiDocument document, String javaPackage, String className) {
        this.document = document;
        this.javaPackage = javaPackage;
        this.className = className;
    }

    String generate(AsyncApiDocument.Channel channel) {
        List<AsyncApiDocument.Message> messages = channel.messages();
        String address = channel.address();
        if (address == null || address.isBlank()) {
            throw new IllegalStateException(
                    "Channel \"" + channel.key() + "\" in " + document.specFile() + " declares no address");
        }

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

        // Reserve the fixed member type names and each message's own "<Name>Payload" record name before any
        // recursive schema generation, so a nested/inline schema that happens to normalize to the same name (e.g.
        // an inline "fooPayload" property inside message "Foo") is rejected instead of silently colliding.
        claimName("EventHeaders", FIXED_TYPE_MARKER);
        claimName("Producer", FIXED_TYPE_MARKER);
        claimName("Consumer", FIXED_TYPE_MARKER);
        claimName("PayloadDeserializer", FIXED_TYPE_MARKER);
        for (String messageJavaName : javaNameByMessage.values()) {
            claimName(messageJavaName + "Payload", FIXED_TYPE_MARKER);
        }

        StringBuilder payloads = new StringBuilder();
        StringBuilder producerMethods = new StringBuilder();
        StringBuilder consumerMethods = new StringBuilder();
        StringBuilder dispatchCases = new StringBuilder();
        Map<String, String> seenEventTypeConstants = new LinkedHashMap<>();

        for (AsyncApiDocument.Message message : messages) {
            String name = javaNameByMessage.get(message.name());
            String eventTypeConstant = eventTypeConstantName(name);
            String collidesWithEventType = seenEventTypeConstants.put(eventTypeConstant, message.name());
            if (collidesWithEventType != null) {
                throw new IllegalStateException("Message names \"" + collidesWithEventType + "\" and \""
                        + message.name() + "\" both generate the EVENT_TYPE constant \"" + eventTypeConstant + "\"");
            }

            payloads.append("    public record ")
                    .append(name)
                    .append("Payload(")
                    .append(recordFields(message.payloadSchema(), message.payloadSchemaFile()))
                    .append(") {}\n");
            payloads.append("    public static final String ")
                    .append(eventTypeConstant)
                    .append(" = \"")
                    .append(stringLiteral(message.name()))
                    .append("\";\n\n");

            producerMethods
                    .append("        boolean publish")
                    .append(name)
                    .append('(')
                    .append(name)
                    .append("Payload payload, EventHeaders headers);\n");
            consumerMethods
                    .append("        void on")
                    .append(name)
                    .append('(')
                    .append(name)
                    .append("Payload payload, EventHeaders headers);\n");
            dispatchCases
                    .append("            case ")
                    .append(eventTypeConstant)
                    .append(" -> on")
                    .append(name)
                    .append("(deserializer.deserialize(rawPayload, ")
                    .append(name)
                    .append("Payload.class), headers);\n");
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
                            String eventType,
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
                        default void dispatch(
                                String eventType,
                                Object rawPayload,
                                EventHeaders headers,
                                PayloadDeserializer deserializer) {
                            switch (eventType) {
                %s            default -> throw new IllegalArgumentException("Unknown eventType: " + eventType);
                            }
                        }
                    }
                }
                """.formatted(
                        javaPackage,
                        className,
                        stringLiteral(address),
                        bindingName(address),
                        className,
                        payloads,
                        nestedTypesSource,
                        producerMethods,
                        consumerMethods,
                        dispatchCases);
    }

    private static String stringLiteral(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private String recordFields(JsonNode objectSchema, Path specFile) {
        AsyncApiDocument.Resolved resolved = document.resolve(objectSchema, specFile);
        Map<String, PropertyInfo> properties = collectProperties(resolved.node(), resolved.file());
        Map<String, String> seenFieldNames = new LinkedHashMap<>();
        StringBuilder fields = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, PropertyInfo> entry : properties.entrySet()) {
            String propertyName = entry.getKey();
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
            PropertyInfo info = entry.getValue();
            fields.append(javaType(info.schema(), info.schemaFile(), propertyName, info.required()))
                    .append(' ')
                    .append(fieldName);
        }
        return fields.toString();
    }

    private record PropertyInfo(JsonNode schema, boolean required, Path schemaFile) {}

    /**
     * A schema with its own {@code properties} is the common case. A schema with no {@code properties} but a
     * {@code oneOf} has none of its own — each branch independently validates the payload — so this merges every
     * branch's properties into one flat record, since a Java record cannot represent a discriminated union. A
     * property present in every branch's own {@code required} list stays required; one that's absent from some
     * branch, or merely optional in some branch, becomes optional overall.
     *
     * @param specFile the file {@code schema} was actually resolved from (not necessarily the entry spec file),
     *     since that's the base every relative child ref in {@code schema} must resolve against
     */
    private Map<String, PropertyInfo> collectProperties(JsonNode schema, Path specFile) {
        if (schema.has(PROPERTIES)) {
            Set<String> required = requiredNames(schema);
            Map<String, PropertyInfo> result = new LinkedHashMap<>();
            JsonNode properties = schema.path(PROPERTIES);
            var fieldNames = properties.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                result.put(name, new PropertyInfo(properties.path(name), required.contains(name), specFile));
            }
            return result;
        }
        if (schema.has(ONE_OF)) {
            return mergeOneOfBranches(schema.path(ONE_OF), specFile);
        }
        return Map.of();
    }

    private Map<String, PropertyInfo> mergeOneOfBranches(JsonNode oneOf, Path specFile) {
        List<AsyncApiDocument.Resolved> branches = new ArrayList<>();
        for (JsonNode branch : oneOf) {
            branches.add(document.resolve(branch, specFile));
        }

        Map<String, JsonNode> propertySchemas = new LinkedHashMap<>();
        Map<String, Path> propertySchemaFiles = new LinkedHashMap<>();
        Map<String, String> propertyJavaTypes = new LinkedHashMap<>();
        for (AsyncApiDocument.Resolved branch : branches) {
            JsonNode properties = branch.node().path(PROPERTIES);
            var fieldNames = properties.fieldNames();
            while (fieldNames.hasNext()) {
                String name = fieldNames.next();
                JsonNode propertySchema = properties.path(name);
                propertySchemas.putIfAbsent(name, propertySchema);
                propertySchemaFiles.putIfAbsent(name, branch.file());
                String javaTypeName = javaType(propertySchema, branch.file(), name, true);
                String previousType = propertyJavaTypes.putIfAbsent(name, javaTypeName);
                if (previousType != null && !previousType.equals(javaTypeName)) {
                    throw new IllegalStateException("oneOf branches in " + specFile
                            + " disagree on the type of property \"" + name + "\": \"" + previousType + "\" vs \""
                            + javaTypeName + "\"");
                }
            }
        }

        Map<String, PropertyInfo> merged = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : propertySchemas.entrySet()) {
            String name = entry.getKey();
            boolean requiredInEveryBranch = branches.stream()
                    .allMatch(branch -> requiredNames(branch.node()).contains(name));
            merged.put(name, new PropertyInfo(entry.getValue(), requiredInEveryBranch, propertySchemaFiles.get(name)));
        }
        return merged;
    }

    private static Set<String> requiredNames(JsonNode schema) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode value : schema.path("required")) {
            names.add(value.asText());
        }
        return names;
    }

    /**
     * {@code required} governs only whether a primitive numeric/boolean type is boxed, so an absent or {@code null}
     * value in the wire payload can be told apart from an actual {@code 0}/{@code false} the producer sent
     * (Jackson otherwise silently defaults a missing primitive field to its zero value). Reference types
     * (String/UUID/Instant/List/nested records/enums) are already nullable regardless of {@code required}.
     */
    private String javaType(JsonNode propertySchema, Path specFile, String contextName, boolean required) {
        String refName = extractRefName(propertySchema);
        AsyncApiDocument.Resolved resolved = document.resolve(propertySchema, specFile);
        JsonNode schema = resolved.node();
        Path effectiveFile = resolved.file();
        String type = schema.path("type").asText();
        // a oneOf schema has no "type" of its own; its merged branches are generated as a record, same as "object"
        if (type.isEmpty() && schema.has(ONE_OF)) {
            type = "object";
        }
        String format = schema.path("format").asText(null);

        return switch (type) {
            case "string" -> stringJavaType(schema, format, refName, contextName);
            case "integer" -> integerJavaType(format, required);
            case "number" -> required ? "double" : "Double";
            case BOOLEAN_TYPE -> required ? BOOLEAN_TYPE : "Boolean";
            case "array" -> {
                // an item present in a list is never itself individually absent, regardless of whether the list
                // property is required
                String itemsType = javaType(schema.path("items"), effectiveFile, contextName + "Item", true);
                yield "List<" + itemsType + ">";
            }
            case "object" -> registerRecord(capitalize(refName != null ? refName : contextName), schema, effectiveFile);
            default -> "Object";
        };
    }

    private static String extractRefName(JsonNode propertySchema) {
        if (!propertySchema.has("$ref")) {
            return null;
        }
        String ref = propertySchema.path("$ref").asText();
        String fragment = ref.contains("#") ? ref.substring(ref.indexOf('#') + 1) : ref;
        int lastSlash = fragment.lastIndexOf('/');
        return lastSlash >= 0 ? fragment.substring(lastSlash + 1) : fragment;
    }

    private String stringJavaType(JsonNode schema, String format, String refName, String contextName) {
        if (schema.has("enum")) {
            return registerEnum(capitalize(refName != null ? refName : contextName), schema);
        }
        if ("uuid".equals(format)) {
            return "UUID";
        }
        if ("date-time".equals(format)) {
            return "Instant";
        }
        return "String";
    }

    private static String integerJavaType(String format, boolean required) {
        if ("int64".equals(format)) {
            return required ? "long" : "Long";
        }
        return required ? "int" : "Integer";
    }

    private String registerEnum(String name, JsonNode schema) {
        claimName(name, schema);
        if (!nestedTypeSources.containsKey(name)) {
            Set<String> seenConstants = new LinkedHashSet<>();
            StringBuilder constants = new StringBuilder();
            boolean first = true;
            for (JsonNode value : schema.path("enum")) {
                String constant = value.asText();
                // isIdentifier() accepts reserved keywords too (they're lexically valid identifiers), so keywords
                // must be rejected separately; isName() would also reject qualified (dotted) names like "FOO.BAR"
                // outright, but that error message is less specific about what's actually wrong.
                if (!SourceVersion.isIdentifier(constant) || RESERVED_WORDS.contains(constant)) {
                    throw new IllegalStateException("Enum value \"" + constant + "\" in schema \"" + name
                            + "\" is not a valid Java identifier");
                }
                if (!seenConstants.add(constant)) {
                    throw new IllegalStateException(
                            "Enum value \"" + constant + "\" in schema \"" + name + "\" is declared more than once");
                }
                if (!first) {
                    constants.append(", ");
                }
                first = false;
                constants.append(constant);
            }
            nestedTypeSources.put(name, "    public enum " + name + " { " + constants + " }");
        }
        return name;
    }

    private String registerRecord(String name, JsonNode schema, Path specFile) {
        claimName(name, schema);
        if (!nestedTypeSources.containsKey(name)) {
            nestedTypeSources.put(name, "PLACEHOLDER");
            String fields = recordFields(schema, specFile);
            nestedTypeSources.put(name, "    public record " + name + "(" + fields + ") {}");
        }
        return name;
    }

    /** Fails fast if two different schemas would both generate the same nested type name. */
    private void claimName(String name, JsonNode schema) {
        JsonNode previous = nestedTypeSchemas.putIfAbsent(name, schema);
        if (previous != null && !previous.equals(schema)) {
            throw new IllegalStateException("Two different schemas both generate the nested type \"" + name + "\"");
        }
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
        if (result.isEmpty()) {
            throw new IllegalStateException("Cannot derive a Java identifier from \"" + value + "\"");
        }
        if (Character.isDigit(result.charAt(0))) {
            result = "Msg" + result;
        }
        return RESERVED_WORDS.contains(result) ? result + "Value" : result;
    }

    private static String capitalize(String value) {
        String name = javaName(value);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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
