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
    private MavenProject project;

    @Parameter(defaultValue = "${project.build.directory}/dependency-specs", readonly = true)
    private String dependencySpecsDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/asyncapi-codegen", readonly = true)
    private String outputDirectory;

    private final Map<String, String> titleBySlug = new LinkedHashMap<>();

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
            String title = document.title();
            String javaPackage = BASE_PACKAGE + "." + slugify(title, specFile);
            Path packageDir = Path.of(outputDirectory, javaPackage.replace('.', '/'));
            Files.createDirectories(packageDir);

            List<AsyncApiDocument.Channel> channels = document.channels();
            boolean singleChannel = channels.size() == 1;
            for (AsyncApiDocument.Channel channel : channels) {
                String className = singleChannel ? "GeneratedChannelContract" : channelClassName(channel.key());
                String source = new JavaContractGenerator(document, javaPackage, className).generate(channel);
                Files.writeString(packageDir.resolve(className + ".java"), source);
                getLog().info("Generated " + javaPackage + "." + className + " from " + specFile);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to generate contract for " + specFile, e);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("Failed to generate contract for " + specFile + ": " + e.getMessage(), e);
        }
    }

    /** Derives a package-safe name from the spec's own {@code info.title}, e.g. "Action events" -> "actionevents". */
    private String slugify(String title, Path specFile) throws MojoExecutionException {
        String slug = title.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
        if (slug.isEmpty()) {
            throw new MojoExecutionException(
                    "Spec " + specFile + " has info.title \"" + title + "\", which has no alphanumeric characters"
                            + " to derive a package name from");
        }
        String previousTitle = titleBySlug.putIfAbsent(slug, title);
        if (previousTitle != null && !previousTitle.equals(title)) {
            throw new MojoExecutionException("info.title \"" + previousTitle + "\" and \"" + title
                    + "\" both derive the same package suffix \"" + slug + "\" (spec: " + specFile + ")");
        }
        return slug;
    }

    /** e.g. "gameEvents" -> "GeneratedGameEventsContract", used only when a document declares multiple channels. */
    private static String channelClassName(String channelKey) {
        String name = JavaContractGenerator.javaName(channelKey);
        return "Generated" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Contract";
    }
}
