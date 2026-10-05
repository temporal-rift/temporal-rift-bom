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

import org.apache.maven.api.Language;
import org.apache.maven.api.Project;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;

/**
 * Generates {@code GeneratedChannelContract.java} for every {@code asyncapi.yml} unpacked under
 * {@code target/dependency-specs} (by the shared {@code unpack-contract-specifications} execution), into a fixed
 * package derived from each spec's own {@code info.title}. Requires no per-service configuration: any service that
 * depends on an {@code io.github.temporal-rift:*-event} artifact gets its contract generated automatically.
 */
@Mojo(name = "generate", defaultPhase = "generate-sources")
public class GenerateChannelContractMojo implements org.apache.maven.api.plugin.Mojo {

    private static final String BASE_PACKAGE = "io.github.temporalrift.asyncapi";

    @Inject
    Log log;

    @Inject
    Project project;

    @Inject
    Session session;

    @Parameter(defaultValue = "${project.build.directory}/dependency-specs", readonly = true)
    Path dependencySpecsDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/asyncapi-codegen", readonly = true)
    Path outputDirectory;

    /** Destination file already written this run, mapped to the spec file that claimed it. */
    private final Map<Path, Path> claimedDestinations = new LinkedHashMap<>();

    @Override
    public void execute() {
        if (!Files.isDirectory(dependencySpecsDirectory)) {
            log.debug("No dependency-specs directory found, nothing to generate: " + dependencySpecsDirectory);
            return;
        }

        List<Path> specFiles = findAsyncApiSpecs(dependencySpecsDirectory);
        if (specFiles.isEmpty()) {
            log.debug("No asyncapi.yml files found under " + dependencySpecsDirectory);
            return;
        }

        clearPriorOutput();
        for (Path specFile : specFiles) {
            generateFor(specFile);
        }

        session.getService(ProjectManager.class)
                .addSourceRoot(project, ProjectScope.MAIN, Language.JAVA_FAMILY, outputDirectory);
    }

    /** Removes output from a prior incremental build so a spec removed since then doesn't leave a stale source file. */
    private void clearPriorOutput() {
        if (!Files.isDirectory(outputDirectory)) {
            return;
        }
        try (var paths = Files.walk(outputDirectory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new MojoException("Failed to clear prior output at " + outputDirectory, e);
        }
    }

    private List<Path> findAsyncApiSpecs(Path specsRoot) {
        List<Path> found = new ArrayList<>();
        try {
            collectAsyncApiSpecs(specsRoot, found);
        } catch (IOException e) {
            throw new MojoException("Failed to scan " + specsRoot + " for asyncapi.yml files", e);
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

    private void generateFor(Path specFile) {
        try {
            AsyncApiDocument document = AsyncApiDocument.parse(specFile);
            String javaPackage = BASE_PACKAGE + "." + slugify(document.title(), specFile);
            Path packageDir = outputDirectory.resolve(javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            List<AsyncApiDocument.Channel> channels = document.channels();
            if (channels.isEmpty()) {
                log.warn("Spec " + specFile + " declares no channels, nothing generated");
                return;
            }
            boolean singleChannel = channels.size() == 1;
            for (AsyncApiDocument.Channel channel : channels) {
                String className = singleChannel ? "GeneratedChannelContract" : channelClassName(channel.key());
                Path destination = packageDir.resolve(className + ".java");
                claimDestination(destination, specFile);

                String source = new JavaContractGenerator(document, javaPackage, className).generate(channel);
                Files.writeString(destination, source);
                log.info("Generated " + javaPackage + "." + className + " from " + specFile);
            }
        } catch (MojoException e) {
            throw e;
        } catch (IOException e) {
            throw new MojoException("Failed to generate contract for " + specFile, e);
        } catch (RuntimeException e) {
            throw new MojoException("Failed to generate contract for " + specFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Rejects any second write to a destination another channel already claimed this run — whether the collision
     * comes from two different spec files (identical info.title, or two different titles that happen to slugify
     * the same way) or from two distinct channels in the same multi-channel spec whose keys produce the same class
     * name (e.g. "gameEvents" and "game-events" both -> GeneratedGameEventsContract). Each channel is claimed
     * exactly once per run, so there is no legitimate reason for a destination to be claimed twice.
     */
    private void claimDestination(Path destination, Path specFile) {
        Path previousSpecFile = claimedDestinations.putIfAbsent(destination, specFile);
        if (previousSpecFile != null) {
            throw new MojoException("Specs " + previousSpecFile + " and " + specFile + " both generate " + destination);
        }
    }

    /** Derives a package-safe name from the spec's own {@code info.title}, e.g. "Action events" -> "actionevents". */
    private static String slugify(String title, Path specFile) {
        String slug = title.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
        if (slug.isEmpty() || !SourceVersion.isName(slug)) {
            throw new MojoException("Spec " + specFile + " has info.title \"" + title
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
