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
  <version>1.0.9</version>
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
| Shared contracts | `domain-events:1.0.4`                      |

## IDE Setup (IntelliJ)

Code style is driven by a workspace-level `.editorconfig` placed one directory above the repository roots
(i.e. alongside `game-service/`, `domain-events/`, etc.). IntelliJ reads it automatically via its built-in
EditorConfig support — no manual scheme import required.

The file contains `root = true` so IntelliJ stops searching at that level and does not pick up any other config
higher up. It includes all IntelliJ-specific `ij_java_*` settings, including the correct import group order
that matches what Checkstyle enforces at build time.

Spotless enforces final formatting at build time (`mvn validate`); the `.editorconfig` handles live editing comfort.

## Commands

```bash
# Format all Java files
mvn spotless:apply

# Check formatting and style
mvn validate
```
