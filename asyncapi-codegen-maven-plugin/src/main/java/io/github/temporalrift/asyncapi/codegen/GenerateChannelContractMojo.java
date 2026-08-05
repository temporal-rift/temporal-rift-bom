package io.github.temporalrift.asyncapi.codegen;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.SourceVersion;

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

    private static final String BASE_PACKAGE = "io.github.temporalrift.asyncapi";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/dependency-specs", readonly = true)
    String dependencySpecsDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/asyncapi-codegen", readonly = true)
    String outputDirectory;

    /** Destination file already written this run, mapped to the spec file that claimed it. */
    private final Map<Path, Path> claimedDestinations = new LinkedHashMap<>();

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

        clearPriorOutput();
        for (Path specFile : specFiles) {
            generateFor(specFile);
        }

        project.addCompileSourceRoot(outputDirectory);
    }

    /** Removes output from a prior incremental build so a spec removed since then doesn't leave a stale source file. */
    private void clearPriorOutput() throws MojoExecutionException {
        Path root = Path.of(outputDirectory);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to clear prior output at " + root, e);
        }
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
            String javaPackage = BASE_PACKAGE + "." + slugify(document.title(), specFile);
            Path packageDir = Path.of(outputDirectory, javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            List<AsyncApiDocument.Channel> channels = document.channels();
            boolean singleChannel = channels.size() == 1;
            for (AsyncApiDocument.Channel channel : channels) {
                String className = singleChannel ? "GeneratedChannelContract" : channelClassName(channel.key());
                Path destination = packageDir.resolve(className + ".java");
                claimDestination(destination, specFile);

                String source = new JavaContractGenerator(document, javaPackage, className).generate(channel);
                Files.writeString(destination, source);
                getLog().info("Generated " + javaPackage + "." + className + " from " + specFile);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate contract for " + specFile, e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Failed to generate contract for " + specFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Rejects any second write to a destination another channel already claimed this run — whether the collision
     * comes from two different spec files (identical info.title, or two different titles that happen to slugify
     * the same way) or from two distinct channels in the same multi-channel spec whose keys produce the same class
     * name (e.g. "gameEvents" and "game-events" both -> GeneratedGameEventsContract). Each channel is claimed
     * exactly once per run, so there is no legitimate reason for a destination to be claimed twice.
     */
    private void claimDestination(Path destination, Path specFile) throws MojoExecutionException {
        Path previousSpecFile = claimedDestinations.putIfAbsent(destination, specFile);
        if (previousSpecFile != null) {
            throw new MojoExecutionException(
                    "Specs " + previousSpecFile + " and " + specFile + " both generate " + destination);
        }
    }

    /** Derives a package-safe name from the spec's own {@code info.title}, e.g. "Action events" -> "actionevents". */
    private static String slugify(String title, Path specFile) throws MojoExecutionException {
        String slug = title.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
        if (slug.isEmpty() || !SourceVersion.isName(slug)) {
            throw new MojoExecutionException("Spec " + specFile + " has info.title \"" + title
                    + "\", which does not produce a valid Java package name segment (got \"" + slug + "\")");
        }
        return slug;
    }

    /** e.g. "gameEvents" -> "GeneratedGameEventsContract", used only when a document declares multiple channels. */
    private static String channelClassName(String channelKey) {
        String name = JavaContractGenerator.javaName(channelKey);
        return "Generated" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Contract";
    }
}
