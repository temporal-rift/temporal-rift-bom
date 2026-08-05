package io.github.temporalrift.asyncapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

class JavaContractGeneratorTest {

    @Test
    void generatesRealActionEventContractThatCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("action-event/asyncapi/asyncapi.yml"));
        AsyncApiDocument.Channel channel = onlyChannel(document);
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.actionevents", "GeneratedChannelContract")
                .generate(channel);

        assertThat(source).contains("public static final String CHANNEL = \"game.events\";");
        assertThat(source).contains("public static final String OUTPUT_BINDING = \"game-events-out\";");

        // schema-derived fields, not a raw json blob
        assertThat(source).contains("public record CardPlayedPayload(");
        assertThat(source).contains("UUID gameId");
        assertThat(source).contains("int eraNumber");

        // nested $ref'd object schema becomes its own record
        assertThat(source).contains("public record ActionSummary(");

        // nested $ref'd string-enum schema becomes a real Java enum, not a plain String
        assertThat(source).contains("public enum CardType {");
        assertThat(source).contains("public enum Faction {");

        // array of $ref'd objects becomes List<Type>
        assertThat(source).contains("List<ActionSummary>");

        // eventType dispatch
        assertThat(source).contains("CARD_PLAYED_EVENT_TYPE = \"CardPlayed\"");
        assertThat(source).contains("case CARD_PLAYED_EVENT_TYPE -> onCardPlayed(");

        compileOrFail(source, "actionevents", "GeneratedChannelContract");
    }

    @Test
    void generatesRealSessionEventContractThatCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("session-event/asyncapi/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.sessionevents", "GeneratedChannelContract")
                .generate(onlyChannel(document));
        assertThat(source).contains("public record EventsDrawnFutureEvent(");
        assertThat(source).contains("public record GameEndedPlayerScoreResult(");
        compileOrFail(source, "sessionevents", "GeneratedChannelContract");
    }

    @Test
    void generatesRealScoringEventContractThatCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("scoring-event/asyncapi/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.scoringevents", "GeneratedChannelContract")
                .generate(onlyChannel(document));
        assertThat(source).contains("public record ScoreUpdate(");
        compileOrFail(source, "scoringevents", "GeneratedChannelContract");
    }

    @Test
    void generatesRealTimelineEventContractThatCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("timeline-event/asyncapi/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.timelineevents", "GeneratedChannelContract")
                .generate(onlyChannel(document));
        assertThat(source).contains("public record EraResolutionCompletedPayload(");

        // the array item type must resolve to the merged record, not fall through to Object
        assertThat(source).contains("List<EraTerminalResolution> terminalResolutions");

        // EraTerminalResolution has no properties of its own, only a oneOf of two branches - the generated record
        // must merge both branches' fields rather than come out empty. winningOutcomeId is required in only one
        // branch, so it must still be present in the merged record (as a nullable UUID, not dropped).
        assertThat(source)
                .contains("public record EraTerminalResolution(UUID eventId, int revealIndex, String terminalState, "
                        + "UUID winningOutcomeId)");

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

        assertThat(gameSource).contains("public final class GeneratedGameEventsContract {");
        assertThat(gameSource).contains("CHANNEL = \"game.events\"");
        assertThat(gameSource).contains("publishLobbyCreated(");
        // a message that belongs only to the other channel must not leak in here
        assertThat(gameSource).doesNotContain("ResolutionStarted");

        assertThat(timelineSource).contains("public final class GeneratedTimelineEventsContract {");
        assertThat(timelineSource).contains("CHANNEL = \"timeline.events\"");
        assertThat(timelineSource).contains("publishResolutionStarted(");
        assertThat(timelineSource).doesNotContain("LobbyCreated");

        compileOrFail(gameSource, "multichannel", "GeneratedGameEventsContract");
        compileOrFail(timelineSource, "multichannel", "GeneratedTimelineEventsContract");
    }

    @Test
    void suffixesReservedWordFieldNamesAndCompiles() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("edge-cases/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.edgecases", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        assertThat(source).contains("String classValue");
        assertThat(source).contains("int defaultValue");

        compileOrFail(source, "edgecases", "GeneratedChannelContract");
    }

    @Test
    void rejectsTwoDifferentSchemasGeneratingTheSameNestedTypeName() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("name-collision/asyncapi.yml"));
        AsyncApiDocument.Channel channel = onlyChannel(document);

        assertThatThrownBy(() -> new JavaContractGenerator(
                                document, "io.github.temporalrift.asyncapi.namecollision", "GeneratedChannelContract")
                        .generate(channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Metadata");
    }

    @Test
    void rejectsCircularRefChains() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("circular-ref/asyncapi.yml"));

        assertThatThrownBy(document::channels)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("circular");
    }

    @Test
    void rejectsEnumValuesThatArentValidJavaIdentifiers() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("invalid-enum/asyncapi.yml"));
        AsyncApiDocument.Channel channel = onlyChannel(document);

        assertThatThrownBy(() -> new JavaContractGenerator(
                                document, "io.github.temporalrift.asyncapi.invalidenum", "GeneratedChannelContract")
                        .generate(channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in-progress");
    }

    @Test
    void rejectsBlankChannelAddress() throws IOException, URISyntaxException {
        AsyncApiDocument.Channel blankAddressChannel = new AsyncApiDocument.Channel("gameEvents", "", List.of());
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("edge-cases/asyncapi.yml"));

        assertThatThrownBy(() -> new JavaContractGenerator(
                                document, "io.github.temporalrift.asyncapi.blank", "GeneratedChannelContract")
                        .generate(blankAddressChannel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no address");
    }

    @Test
    void boxesOptionalNumericAndBooleanFieldsButNotRequiredOnes() throws IOException, URISyntaxException {
        AsyncApiDocument document = AsyncApiDocument.parse(fixture("optional-fields/asyncapi.yml"));
        String source = new JavaContractGenerator(
                        document, "io.github.temporalrift.asyncapi.optionalfields", "GeneratedChannelContract")
                .generate(onlyChannel(document));

        assertThat(source).contains("int requiredCount");
        assertThat(source).contains("Integer optionalCount");
        assertThat(source).contains("long requiredTotal");
        assertThat(source).contains("Long optionalTotal");
        assertThat(source).contains("double requiredScore");
        assertThat(source).contains("Double optionalScore");
        assertThat(source).contains("boolean requiredFlag");
        assertThat(source).contains("Boolean optionalFlag");

        compileOrFail(source, "optionalfields", "GeneratedChannelContract");
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
