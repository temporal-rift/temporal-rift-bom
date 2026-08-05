package io.github.temporalrift.asyncapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class GenerateChannelContractMojoTest {

    private static final String SPEC_TEMPLATE = """
            asyncapi: 3.0.0
            info:
              title: %s
              version: 1.0.0
            servers:
              kafka:
                host: localhost:9092
                protocol: kafka
            channels:
              gameEvents:
                address: game.events
                messages:
                  %s:
                    $ref: '#/components/messages/%s'
            operations:
              publish%s:
                action: send
                channel: { $ref: '#/channels/gameEvents' }
                messages: [ { $ref: '#/channels/gameEvents/messages/%s' } ]
            components:
              messages:
                %s:
                  name: %s
                  payload:
                    type: object
                    properties:
                      gameId: { type: string, format: uuid }
                    required: [ gameId ]
            """;

    @Test
    void rejectsTwoDifferentSpecFilesWithTheIdenticalTitle(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws IOException {
        // Two unrelated specs that happen to share the exact same info.title — not just a slug collision from
        // differently-punctuated titles, the literal same title string, which the naive putIfAbsent-on-title-map
        // approach would treat as "already seen, fine" and let silently overwrite.
        writeSpec(tempDir, "first-spec", "Shared Title", "thingOne", "ThingOne");
        writeSpec(tempDir, "second-spec", "Shared Title", "thingTwo", "ThingTwo");

        GenerateChannelContractMojo mojo = new GenerateChannelContractMojo();
        mojo.project = new MavenProject();
        mojo.dependencySpecsDirectory = tempDir.resolve("dependency-specs").toString();
        mojo.outputDirectory = tempDir.resolve("generated-sources").toString();

        assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("first-spec")
                .hasMessageContaining("second-spec")
                .hasMessageContaining("GeneratedChannelContract.java");
    }

    @Test
    void generatesDistinctPackagesForSpecsWithDifferentTitles(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        writeSpec(tempDir, "first-spec", "First Title", "thingOne", "ThingOne");
        writeSpec(tempDir, "second-spec", "Second Title", "thingTwo", "ThingTwo");

        GenerateChannelContractMojo mojo = new GenerateChannelContractMojo();
        mojo.project = new MavenProject();
        mojo.dependencySpecsDirectory = tempDir.resolve("dependency-specs").toString();
        mojo.outputDirectory = tempDir.resolve("generated-sources").toString();

        mojo.execute();

        assertThat(tempDir.resolve("generated-sources/io/github/temporalrift/asyncapi/firsttitle/GeneratedChannelContract.java"))
                .exists();
        assertThat(tempDir.resolve("generated-sources/io/github/temporalrift/asyncapi/secondtitle/GeneratedChannelContract.java"))
                .exists();
    }

    private static void writeSpec(Path tempDir, String specDirName, String title, String messageKey, String messageName)
            throws IOException {
        Path specDir = tempDir.resolve("dependency-specs").resolve(specDirName).resolve("asyncapi");
        Files.createDirectories(specDir);
        Files.writeString(
                specDir.resolve("asyncapi.yml"),
                SPEC_TEMPLATE.formatted(
                        title, messageKey, messageName, messageName, messageKey, messageName, messageName));
    }
}
