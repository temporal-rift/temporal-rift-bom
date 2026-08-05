package io.github.temporalrift.asyncapi.codegen;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Generates {@code GeneratedChannelContract.java} for every {@code asyncapi.yml} unpacked under
 * {@code target/dependency-specs} (by the shared {@code unpack-contract-specifications} execution), into a fixed
 * package derived from each spec's own {@code info.title}. Requires no per-service configuration: any service that
 * depends on an {@code io.github.temporal-rift:*-event} artifact gets its contract generated automatically.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateChannelContractMojo extends AbstractMojo {

    private static final String BASE_PACKAGE = "io.github.temporalrift.generated.asyncapi";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/dependency-specs", readonly = true)
    private String dependencySpecsDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/asyncapi-codegen", readonly = true)
    private String outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        Path specsRoot = Path.of(dependencySpecsDirectory);
        if (!Files.isDirectory(specsRoot)) {
            getLog().debug("No dependency-specs directory found, nothing to generate: " + specsRoot);
            return;
        }

        List<Path> specFiles = findAsyncApiSpecs(specsRoot);
        if (specFiles.isEmpty()) {
            getLog().debug("No asyncapi.yml files found under " + specsRoot);
            return;
        }

        for (Path specFile : specFiles) {
            generateFor(specFile);
        }

        project.addCompileSourceRoot(outputDirectory);
    }

    private List<Path> findAsyncApiSpecs(Path specsRoot) throws MojoExecutionException {
        List<Path> found = new ArrayList<>();
        try {
            collectAsyncApiSpecs(specsRoot, found);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to scan " + specsRoot + " for asyncapi.yml files", e);
        }
        return found;
    }

    private void collectAsyncApiSpecs(Path dir, List<Path> found) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    collectAsyncApiSpecs(entry, found);
                } else if (entry.getFileName().toString().equals("asyncapi.yml")) {
                    found.add(entry);
                }
            }
        }
    }

    private void generateFor(Path specFile) throws MojoExecutionException {
        try {
            AsyncApiDocument document = AsyncApiDocument.parse(specFile);
            String specName = slugify(document.title());
            String javaPackage = BASE_PACKAGE + "." + specName;
            String source = new JavaContractGenerator(document, javaPackage).generate();

            Path packageDir = Path.of(outputDirectory, javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);
            Files.writeString(packageDir.resolve("GeneratedChannelContract.java"), source);
            getLog().info("Generated " + javaPackage + ".GeneratedChannelContract from " + specFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate contract for " + specFile, e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Failed to generate contract for " + specFile + ": " + e.getMessage(), e);
        }
    }

    /** Derives a package-safe name from the spec's own {@code info.title}, e.g. "Action events" -> "actionevents". */
    private static String slugify(String title) {
        return title.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }
}
