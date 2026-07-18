# temporal-rift-bom

Parent BOM for Temporal Rift services. Provides unified dependency management, formatting (Spotless + Palantir)
and code quality (Checkstyle).

## Structure

```
temporal-rift-bom/
├── pom.xml             ← parent for all Temporal Rift services
└── build-tools/
    ├── pom.xml         ← standalone jar, built separately
    └── src/main/resources/
        └── checkstyle.xml
```

`build-tools` is intentionally **not** a Maven reactor module of the root BOM, because the root BOM's Checkstyle
plugin depends on `build-tools` at resolution time.
Listing it as a module would create a circular plugin dependency.

## Build order

`build-tools` must be published to Maven Central **before** the root BOM.
The CI workflow (`publish.yml`) handles this automatically in a single job.

### Manual publishing (requires Sonatype credentials + GPG key in `~/.m2/settings.xml`)

```bash
# 1. Publish build-tools
mvn -f build-tools/pom.xml deploy -Pcentral

# 2. Publish root BOM
mvn deploy -Pcentral -Denforcer.skip=true
```

## Usage in services

```xml

<parent>
  <groupId>io.github.temporal-rift</groupId>
  <artifactId>temporal-rift-bom</artifactId>
  <version>1.4.0</version>
</parent>
```

## What's included

| Category         | Plugin / Dependency                        |
|------------------|--------------------------------------------|
| Formatting       | Spotless + Palantir Java Format 2.90.0     |
| Code quality     | Checkstyle 13.2.0 (Google style, adapted)  |
| Enforcement      | Maven Enforcer (Java 25, Maven 3.9.13+)    |
| Coverage         | JaCoCo (managed, opt-in per service)       |
| API generation   | OpenAPI Generator 7.20.0 (managed, opt-in) |
| Event codegen    | ZenWave SDK 2.5.4 (managed, opt-in)        |
| Shared contracts | `domain-events:1.0.4`                      |

## Generating code from an `apis` AsyncAPI spec

The `apis` repo publishes spec-only dependencies (an `asyncapi.yml` packaged as a plain resource, no
generated code). A service that wants producer/consumer code from one adds the spec as a dependency and
declares its own `<execution>` — plugin identity, the generator dependency, and shared defaults
(`generatorName`, `templates`, `transactionalOutbox`, `generateMessageHeaders`) are already managed here, so
the consumer only supplies what's specific to that spec:

```xml
<dependency>
    <groupId>io.github.temporal-rift</groupId>
    <artifactId>session-event</artifactId>
    <version>1.0.0</version>
</dependency>
```

```xml
<plugin>
    <groupId>io.zenwave360.sdk</groupId>
    <artifactId>zenwave-sdk-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>generate-session-events</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>generate</goal>
            </goals>
            <configuration>
                <inputSpec>classpath:asyncapi/asyncapi.yml</inputSpec>
                <configOptions>
                    <role>provider</role>
                    <modelPackage>your.own.package.events.model</modelPackage>
                    <producerApiPackage>your.own.package.events.producer</producerApiPackage>
                </configOptions>
            </configuration>
        </execution>
    </executions>
</plugin>
```

`role=client` generates a consumer instead — use `consumerApiPackage` in that case. This mirrors the
existing OpenAPI Generator split above: identity and defaults centralized here, per-spec `inputSpec` and
target packages declared where the spec is actually consumed.

## IDE Setup (IntelliJ)

Code style is driven by a workspace-level `.editorconfig`. The file lives at the root of this repo and must be
copied **one level above** all cloned service repositories so IntelliJ finds it as the workspace root:

```
your-workspace/          ← copy .editorconfig here
├── temporal-rift-bom/
├── game-service/
├── domain-events/
└── ...
```

```bash
# from your workspace root
cp temporal-rift-bom/.editorconfig .
```

IntelliJ reads it automatically via its built-in EditorConfig support — no manual scheme import required.
The `root = true` header stops IntelliJ from searching further up the filesystem. It includes all
`ij_java_*` settings, including the import group order that matches what Checkstyle enforces at build time.

Spotless enforces final formatting at build time (`mvn validate`); the `.editorconfig` handles live editing comfort.

## Commands

```bash
# Format all Java files
mvn spotless:apply

# Check formatting and style
mvn validate
```
