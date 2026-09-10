package io.github.temporalrift.asyncapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.MismatchedInputException;

class JavaContractGeneratorTest {

    @Test
    void generatesRealActionEventContractThatCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("action-event/asyncapi/asyncapi.yml"));
        AsyncApiDocument.Channel channel = onlyChannel(document);
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.actionevents", "GeneratedChannelContract")
                .generate(channel);

        assertThat(source)
                .contains("public static final String CHANNEL = \"game.events\";")
                .contains("public static final String OUTPUT_BINDING = \"game-events-out\";")
                // schema-derived fields, not a raw json blob
                .contains("public record CardPlayedPayload(")
                .contains("UUID gameId")
                .contains("int eraNumber")
                // nested $ref'd object schema becomes its own record
                .contains("public record ActionSummary(")
                // nested $ref'd string-enum schema becomes a real Java enum, not a plain String
                .contains("public enum CardType {")
                .contains("public enum Faction {")
                // array of $ref'd objects becomes List<Type>
                .contains("List<ActionSummary>")
                .contains("@Size(min = 1, max = 3) @UniqueElements List<UUID> targetEventIds")
                // eventType dispatch
                .contains("CARD_PLAYED_EVENT_TYPE = \"CardPlayed\"")
                .contains("case CARD_PLAYED_EVENT_TYPE -> {")
                .contains("onCardPlayed(deserializer.deserialize(rawPayload, CardPlayedPayload.class), headers);")
                .contains("yield true;")
                .contains("default boolean dispatch(")
                .contains("if (eventType == null) {")
                .contains("return switch (eventType) {");

        compileOrFail(source, "actionevents", "GeneratedChannelContract");
    }

    @Test
    void generatesRealSessionEventContractThatCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("session-event/asyncapi/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.sessionevents", "GeneratedChannelContract")
                .generate(onlyChannel(document));
        assertThat(source)
                .contains("public record EventsDrawnFutureEvent(")
                .contains("public record GameEndedPlayerScoreResult(")
                .contains("@NotNull @Valid @Size(min = 7, max = 7) List<HandDealtCardInstance> cards");
        compileOrFail(source, "sessionevents", "GeneratedChannelContract");
    }

    @Test
    void generatesRealScoringEventContractThatCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("scoring-event/asyncapi/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.scoringevents", "GeneratedChannelContract")
                .generate(onlyChannel(document));
        assertThat(source)
                .contains("public record ScoreUpdate(")
                .contains("public record ScoresUpdatedPayload(@JsonProperty(required = true) @NotNull UUID gameId");
        compileOrFail(source, "scoringevents", "GeneratedChannelContract");
    }

    @Test
    void generatesRealTimelineEventContractThatCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("timeline-event/asyncapi/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.timelineevents", "GeneratedChannelContract")
                .generate(onlyChannel(document));
        assertThat(source)
                .contains("public record EraResolutionCompletedPayload(")
                // the array item type must resolve to the merged record, not fall through to Object
                .contains("List<EraTerminalResolution> terminalResolutions")
                // EraTerminalResolution has no properties of its own, only a oneOf of two branches - the generated
                // record must merge both branches' fields rather than come out empty. winningOutcomeId is required
                // in only one branch, so it must still be present in the merged record (as a nullable UUID, not
                // dropped).
                .contains("public record EraTerminalResolution(@JsonProperty(required = true) @NotNull UUID eventId, "
                        + "@JsonProperty(required = true) @DecimalMin(value = \"0\") int revealIndex, "
                        + "@JsonProperty(required = true) @NotNull String terminalState, UUID winningOutcomeId)");

        compileOrFail(source, "timelineevents", "GeneratedChannelContract");
    }

    @Test
    void generatesOneIndependentContractPerChannel() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("multi-channel/asyncapi.yml"));
        List<AsyncApiDocument.Channel> channels = document.channels();
        assertThat(channels).hasSize(2);

        AsyncApiDocument.Channel gameEvents = channels.get(0);
        AsyncApiDocument.Channel timelineEvents = channels.get(1);
        assertThat(gameEvents.address()).isEqualTo("game.events");
        assertThat(timelineEvents.address()).isEqualTo("timeline.events");

        String gameSource = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.multichannel", "GeneratedGameEventsContract")
                .generate(gameEvents);
        String timelineSource = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.multichannel", "GeneratedTimelineEventsContract")
                .generate(timelineEvents);

        assertThat(gameSource)
                .contains("public final class GeneratedGameEventsContract {")
                .contains("CHANNEL = \"game.events\"")
                .contains("publishLobbyCreated(")
                // a message that belongs only to the other channel must not leak in here
                .doesNotContain("ResolutionStarted");

        assertThat(timelineSource)
                .contains("public final class GeneratedTimelineEventsContract {")
                .contains("CHANNEL = \"timeline.events\"")
                .contains("publishResolutionStarted(")
                .doesNotContain("LobbyCreated");

        compileOrFail(gameSource, "multichannel", "GeneratedGameEventsContract");
        compileOrFail(timelineSource, "multichannel", "GeneratedTimelineEventsContract");
    }

    @Test
    void suffixesReservedWordFieldNamesAndCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("edge-cases/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.edgecases", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        assertThat(source).contains("String classValue").contains("int defaultValue");

        compileOrFail(source, "edgecases", "GeneratedChannelContract");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("singleMessageRejectionCases")
    void rejectsInvalidSchemas(String testName, String fixtureDir, String packageSuffix, String expectedMessage)
            throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture(fixtureDir + "/asyncapi.yml"));
        AsyncApiDocument.Channel channel = onlyChannel(document);

        var generator = new JavaContractGenerator(
                document, "io.github.temporalrift.asyncapi." + packageSuffix, "GeneratedChannelContract");
        assertThatThrownBy(() -> generator.generate(channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedMessage);
    }

    private static Stream<Arguments> singleMessageRejectionCases() {
        return Stream.of(
                Arguments.of(
                        "rejectsTwoDifferentSchemasGeneratingTheSameNestedTypeName",
                        "name-collision",
                        "namecollision",
                        "Metadata"),
                Arguments.of(
                        "rejectsEnumValuesThatArentValidJavaIdentifiers", "invalid-enum", "invalidenum", "in-progress"),
                // message "Foo" generates "FooPayload"; its own payload has an inline "fooPayload" object property
                // that normalizes to the same name - the fixed-name reservation must catch this, not silently let
                // it collide.
                Arguments.of(
                        "rejectsAnInlineSchemaCollidingWithAGeneratedPayloadRecordName",
                        "fixed-name-collision",
                        "fixednamecollision",
                        "FooPayload"),
                Arguments.of("rejectsQualifiedEnumValues", "qualified-enum-value", "qualifiedenumvalue", "FOO.BAR"),
                Arguments.of(
                        "rejectsHeterogeneousTypeArrays",
                        "heterogeneous-type-array",
                        "heterogeneoustypearray",
                        "Only nullable single-type unions are supported"),
                // an inline "generatedChannelContract" property normalizes to the same name as the enclosing class
                // itself
                Arguments.of(
                        "rejectsANestedSchemaCollidingWithTheEnclosingClassName",
                        "class-name-collision",
                        "classnamecollision",
                        "GeneratedChannelContract"));
    }

    @Test
    void rejectsCircularRefChains() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("circular-ref/asyncapi.yml"));

        assertThatThrownBy(document::channels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("circular");
    }

    @Test
    void rejectsBlankChannelAddress() throws IOException, URISyntaxException {
        AsyncApiDocument.Channel blankAddressChannel = new AsyncApiDocument.Channel("gameEvents", "", List.of());
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("edge-cases/asyncapi.yml"));

        var generator = new JavaContractGenerator(
                document, "io.github.temporalrift.asyncapi.blank", "GeneratedChannelContract");
        assertThatThrownBy(() -> generator.generate(blankAddressChannel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no address");
    }

    @Test
    void boxesOptionalNumericAndBooleanFieldsButNotRequiredOnes() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("optional-fields/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.optionalfields", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        assertThat(source)
                .contains("int requiredCount")
                .contains("Integer optionalCount")
                .contains("long requiredTotal")
                .contains("Long optionalTotal")
                .contains("double requiredScore")
                .contains("Double optionalScore")
                .contains("boolean requiredFlag")
                .contains("Boolean optionalFlag");

        compileOrFail(source, "optionalfields", "GeneratedChannelContract");
    }

    @Test
    void generatesSchemaValidationAnnotationsAndEnforcesThemAtRuntime()
            throws IOException, URISyntaxException, ReflectiveOperationException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("validation-constraints/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.validationconstraints", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        assertThat(source)
                .contains("import jakarta.validation.Valid;")
                .contains("import org.hibernate.validator.constraints.UniqueElements;")
                .contains("@JsonProperty(required = true) @NotNull @Size(min = 1, max = 3) @UniqueElements List<UUID>")
                .contains(
                        "@JsonProperty(required = true) @NotNull @Size(min = 2, max = 5) @Pattern(regexp = \"[A-Z]+\")")
                .contains("@JsonProperty(required = true) @DecimalMin(value = \"1\") @DecimalMax(value = \"3\") int")
                .contains("@DecimalMin(value = \"0.5\", inclusive = false) "
                        + "@DecimalMax(value = \"4.5\", inclusive = false)")
                .contains("@JsonProperty(required = true) String nullableName")
                .doesNotContain("@NotNull String nullableName")
                .contains("@JsonProperty(required = true) @NotNull @Valid Metadata metadata")
                .contains("Integer nullableCount")
                .contains("Double nullableScore")
                .contains("Boolean nullableFlag")
                .contains("@JsonProperty(required = true) @Size(min = 1, max = 2) List<String> nullableTags")
                .contains("@JsonProperty(required = true) @Valid NullableMetadata nullableMetadata")
                .contains("@JsonProperty(required = true) @Valid List<NullableMetadataListItem> nullableMetadataList")
                .contains("Kind kind")
                .doesNotContain("@Size(min = 2) @Pattern(regexp = \"[A-Z]+\") Kind kind")
                .contains(
                        "public record Metadata(@JsonProperty(required = true) @DecimalMin(value = \"1\") int order)");

        Class<?> contract = compileAndLoad(source, "validationconstraints", "GeneratedChannelContract");
        Class<?> payloadType =
                Class.forName(contract.getName() + "$ThingHappenedPayload", true, contract.getClassLoader());
        Class<?> metadataType = Class.forName(contract.getName() + "$Metadata", true, contract.getClassLoader());
        Class<?> nullableMetadataType =
                Class.forName(contract.getName() + "$NullableMetadata", true, contract.getClassLoader());
        Object validMetadata = metadataType.getDeclaredConstructor(int.class).newInstance(1);
        Object validPayload = payloadType
                .getDeclaredConstructor(
                        List.class,
                        String.class,
                        int.class,
                        Double.class,
                        String.class,
                        String.class,
                        metadataType,
                        Integer.class,
                        Double.class,
                        Boolean.class,
                        List.class,
                        nullableMetadataType,
                        List.class,
                        Class.forName(contract.getName() + "$Kind", true, contract.getClassLoader()))
                .newInstance(
                        List.of(java.util.UUID.randomUUID()),
                        "AB",
                        1,
                        1.0,
                        "required",
                        null,
                        validMetadata,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        Object invalidMetadata = metadataType.getDeclaredConstructor(int.class).newInstance(0);
        java.util.UUID duplicateId = java.util.UUID.randomUUID();
        Object invalidPayload = payloadType
                .getDeclaredConstructor(
                        List.class,
                        String.class,
                        int.class,
                        Double.class,
                        String.class,
                        String.class,
                        metadataType,
                        Integer.class,
                        Double.class,
                        Boolean.class,
                        List.class,
                        nullableMetadataType,
                        List.class,
                        Class.forName(contract.getName() + "$Kind", true, contract.getClassLoader()))
                .newInstance(
                        List.of(duplicateId, duplicateId),
                        "a",
                        0,
                        0.5,
                        null,
                        null,
                        invalidMetadata,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        try (ValidatorFactory validatorFactory = Validation.byDefaultProvider()
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()) {
            var validator = validatorFactory.getValidator();
            assertThat(validator.validate(validPayload)).isEmpty();

            Set<ConstraintViolation<Object>> violations = validator.validate(invalidPayload);
            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("targetEventIds", "code", "roundNumber", "weight", "requiredName", "metadata.order")
                    .doesNotContain("nullableName");
        }

        ObjectMapper mapper = new ObjectMapper();
        Object deserialized = mapper.readValue("""
                {"targetEventIds":["3fa85f64-5717-4562-b3fc-2c963f66afa6"],"code":"AB","roundNumber":1,
                "weight":1.0,"requiredName":"required","nullableName":null,"metadata":{"order":1},
                "nullableCount":null,"nullableScore":null,"nullableFlag":null,"nullableTags":null,
                "nullableMetadata":null,"nullableMetadataList":null}
                """, payloadType);
        assertThat(payloadType.getMethod("nullableCount").invoke(deserialized)).isNull();
        assertThat(payloadType.getMethod("nullableScore").invoke(deserialized)).isNull();
        assertThat(payloadType.getMethod("nullableFlag").invoke(deserialized)).isNull();
        assertThat(payloadType.getMethod("nullableTags").invoke(deserialized)).isNull();
        assertThat(payloadType.getMethod("nullableMetadata").invoke(deserialized))
                .isNull();
        assertThat(payloadType.getMethod("nullableMetadataList").invoke(deserialized))
                .isNull();
    }

    @Test
    void normalizesRefFragmentNamesBeforeDeclaringGeneratedTypes() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("ref-name-normalization/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.refnamenormalization", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        assertThat(source)
                // "#/components/schemas/status-type" must not emit the raw, non-identifier fragment "status-type"
                .contains("public enum StatusType {")
                // "#/components/schemas/new" is both hyphen-free and a reserved word - the existing Value-suffix
                // path applies
                .contains("public record NewValue(");

        compileOrFail(source, "refnamenormalization", "GeneratedChannelContract");
    }

    @Test
    void resolvesRelativeRefsAgainstTheFileTheyActuallyAppearIn() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("cross-file-ref/asyncapi/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.crossfileref", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        // FooPayload comes from shared/payload.yaml; its "status" property is "./enums.yaml#/Status", relative to
        // shared/payload.yaml (-> shared/enums.yaml), not relative to the entry asyncapi.yml's own directory.
        assertThat(source)
                .contains("public enum Status {")
                .contains("ACTIVE, INACTIVE, UNKNOWN;")
                .contains("public static Status fromWireValue(String value) {")
                .contains("Status status");

        compileOrFail(source, "crossfileref", "GeneratedChannelContract");
    }

    @Test
    void rejectsEventTypeConstantCollisionsFromDifferentlyCasedMessageNames() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("event-type-collision/asyncapi.yml"));
        AsyncApiDocument.Channel channel = onlyChannel(document);

        var generator = new JavaContractGenerator(
                document, "io.github.temporalrift.asyncapi.eventtypecollision", "GeneratedChannelContract");
        assertThatThrownBy(() -> generator.generate(channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AB")
                .hasMessageContaining("aB")
                .hasMessageContaining("EVENT_TYPE");
    }

    @Test
    void rejectsDuplicateEnumValues() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("duplicate-enum-value/asyncapi.yml"));
        AsyncApiDocument.Channel channel = onlyChannel(document);

        var generator = new JavaContractGenerator(
                document, "io.github.temporalrift.asyncapi.duplicateenumvalue", "GeneratedChannelContract");
        assertThatThrownBy(() -> generator.generate(channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE")
                .hasMessageContaining("more than once");
    }

    @Test
    void capitalizesLowercaseStartingMessageNamesInGeneratedTypeNames() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("lowercase-message-name/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.lowercasemessagename", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        // message name "thingHappened" must still produce a capitalized record/method name, not "thingHappenedPayload"
        assertThat(source)
                .contains("public record ThingHappenedPayload(")
                .contains("publishThingHappened(")
                .contains("onThingHappened(");

        compileOrFail(source, "lowercasemessagename", "GeneratedChannelContract");
    }

    @Test
    void rejectsPayloadSchemasWithNeitherPropertiesNorOneOf() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("unsupported-schema-shape/asyncapi.yml"));
        AsyncApiDocument.Channel channel = onlyChannel(document);

        var generator = new JavaContractGenerator(
                document, "io.github.temporalrift.asyncapi.unsupportedschemashape", "GeneratedChannelContract");
        assertThatThrownBy(() -> generator.generate(channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("properties")
                .hasMessageContaining("oneOf");
    }

    private static AsyncApiDocument.Channel onlyChannel(AsyncApiDocument document) {
        List<AsyncApiDocument.Channel> channels = document.channels();
        assertThat(channels).hasSize(1);
        return channels.get(0);
    }

    private static Path fixture(String relativePath) throws URISyntaxException {
        URL resource = JavaContractGeneratorTest.class.getClassLoader().getResource("fixtures/" + relativePath);
        assertThat(resource).as("fixture " + relativePath).isNotNull();
        return Path.of(resource.toURI());
    }

    /**
     * Behavioural, not source-string, verification: compiles and loads the generated record, then deserializes
     * real JSON through {@code tools.jackson.databind} (the same major version the consuming services run) to
     * confirm required-field and tolerant-enum semantics actually hold at runtime, not just that the expected
     * annotations appear in the generated source.
     */
    @Test
    void requiredReferenceFieldsRejectAbsenceButAllowNull_unknownEnumValueNormalizesToUnknown()
            throws IOException, URISyntaxException, ReflectiveOperationException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("required-reference-fields/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.requiredfields", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        Class<?> contract = compileAndLoad(source, "requiredfields", "GeneratedChannelContract");
        Class<?> payloadType =
                Class.forName(contract.getName() + "$ThingHappenedPayload", true, contract.getClassLoader());
        Class<?> statusType = Class.forName(contract.getName() + "$Status", true, contract.getClassLoader());
        ObjectMapper mapper = new ObjectMapper();

        String gameId = "\"gameId\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"";
        String requiredIds = "\"requiredIds\":[]";
        String requiredThing = "\"requiredThing\":{\"value\":\"x\"}";
        String status = "\"status\":\"ACTIVE\"";

        // Every required field present -> succeeds.
        mapper.readValue("{%s,%s,%s,%s}".formatted(gameId, requiredIds, requiredThing, status), payloadType);

        // A required list absent entirely -> rejected (missing means invalid, per JSON Schema "required").
        String missingRequiredIds = "{%s,%s,%s}".formatted(gameId, requiredThing, status);
        assertThatThrownBy(() -> mapper.readValue(missingRequiredIds, payloadType))
                .isInstanceOf(MismatchedInputException.class);

        // A required list explicitly null -> allowed: JSON Schema "required" means present, not non-null.
        Object withNullList = mapper.readValue(
                "{%s,\"requiredIds\":null,%s,%s}".formatted(gameId, requiredThing, status), payloadType);
        assertThat(payloadType.getMethod("requiredIds").invoke(withNullList)).isNull();

        // A required nested object absent entirely -> rejected.
        String missingRequiredThing = "{%s,%s,%s}".formatted(gameId, requiredIds, status);
        assertThatThrownBy(() -> mapper.readValue(missingRequiredThing, payloadType))
                .isInstanceOf(MismatchedInputException.class);

        // A required enum absent entirely -> rejected.
        String missingStatus = "{%s,%s,%s}".formatted(gameId, requiredIds, requiredThing);
        assertThatThrownBy(() -> mapper.readValue(missingStatus, payloadType))
                .isInstanceOf(MismatchedInputException.class);

        // An enum value the schema never declared -> normalizes to UNKNOWN rather than failing deserialization.
        Object withUnknownStatus = mapper.readValue(
                "{%s,%s,%s,\"status\":\"SOMETHING_A_NEWER_PRODUCER_ADDED\"}"
                        .formatted(gameId, requiredIds, requiredThing),
                payloadType);
        Object statusValue = payloadType.getMethod("status").invoke(withUnknownStatus);
        Object unknownConstant = statusType.getMethod("valueOf", String.class).invoke(null, "UNKNOWN");
        assertThat(statusValue).isEqualTo(unknownConstant);
    }

    private static Class<?> compileAndLoad(String source, String specPackage, String className)
            throws IOException, ReflectiveOperationException {
        Path tempDir = Files.createTempDirectory("asyncapi-codegen-test");
        Path packageDir = tempDir.resolve("io/github/temporalrift/asyncapi/" + specPackage);
        Files.createDirectories(packageDir);
        Path sourceFile = packageDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path outDir = Files.createDirectory(tempDir.resolve("out"));
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            var task = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    List.of("-d", outDir.toString()),
                    null,
                    fileManager.getJavaFileObjects(sourceFile));
            assertThat(task.call())
                    .as("generated source must compile:\n" + source)
                    .isTrue();
        }

        URLClassLoader loader = new URLClassLoader(
                new URL[] {outDir.toUri().toURL()}, JavaContractGeneratorTest.class.getClassLoader());
        return Class.forName("io.github.temporalrift.asyncapi." + specPackage + "." + className, true, loader);
    }

    private static void compileOrFail(String source, String specPackage, String className) throws IOException {
        Path tempDir = Files.createTempDirectory("asyncapi-codegen-test");
        Path packageDir = tempDir.resolve("io/github/temporalrift/asyncapi/" + specPackage);
        Files.createDirectories(packageDir);
        Path sourceFile = packageDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Path outDir = Files.createDirectory(tempDir.resolve("out"));
            var task = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    List.of("-d", outDir.toString()),
                    null,
                    fileManager.getJavaFileObjects(sourceFile));
            boolean success = task.call();
            assertThat(success).as("generated source must compile:\n" + source).isTrue();
        }
    }
}
