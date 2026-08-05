package io.github.temporalrift.asyncapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

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
        Path specFile = fixture("action-event/asyncapi/asyncapi.yml");

        AsyncApiDocument document = AsyncApiDocument.parse(specFile);
        String source = new JavaContractGenerator(document, "io.github.temporalrift.generated.asyncapi.actionevents")
                .generate();

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

        compileOrFail(source);
    }

    private static Path fixture(String relativePath) throws URISyntaxException {
        URL resource = JavaContractGeneratorTest.class.getClassLoader().getResource("fixtures/" + relativePath);
        assertThat(resource).as("fixture " + relativePath).isNotNull();
        return Path.of(resource.toURI());
    }

    private static void compileOrFail(String source) throws IOException {
        Path tempDir = Files.createTempDirectory("asyncapi-codegen-test");
        Path packageDir = tempDir.resolve("io/github/temporalrift/generated/asyncapi/actionevents");
        Files.createDirectories(packageDir);
        Path sourceFile = packageDir.resolve("GeneratedChannelContract.java");
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
