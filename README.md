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

The `main`-branch publishing workflow releases `build-tools` and the root BOM in this order. Do not use local Maven
publication to bridge a consumer to an unreleased BOM version.

## Usage in services

```xml

<parent>
  <groupId>io.github.temporal-rift</groupId>
  <artifactId>temporal-rift-bom</artifactId>
  <version>1.7.0</version>
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
| Contract resources | Generic unpacking of spec-only OpenAPI and AsyncAPI modules from `apis` |

## Generating code from an `apis` contract

The `apis` repo publishes spec-only OpenAPI and AsyncAPI dependencies. This BOM extracts `openapi/**` and
`asyncapi/**` from every Temporal Rift dependency during `initialize`, before code generation. Each artifact is
isolated at `${project.build.directory}/dependency-specs/{artifactId}-{version}-jar/`, so same-named AsyncAPI resources
cannot overwrite one another. A consumer adds only the contract dependency and the generator execution whose package
and role are specific to that service.

```xml
<dependency>
    <groupId>io.github.temporal-rift</groupId>
    <artifactId>session-event</artifactId>
    <version>1.0.0</version>
</dependency>
```


`role=client` generates a consumer instead — use `consumerApiPackage` in that case. For a REST contract, OpenAPI
modules version their URL path prefix as a folder under `openapi/` (`v1/`, `v2/`, ...) — use the matching
version-qualified path such as
`${project.build.directory}/dependency-specs/session-api-2.0.0-jar/openapi/v1/session.yml` as the OpenAPI Generator
input, and give that execution's `apiPackage`/`modelPackage` a matching `.v1` suffix so generating more than one
version of the same contract doesn't collide. Plugin identity and shared defaults remain centralised here;
generated package, output, and AsyncAPI role remain consumer-specific.

## IDE Setup (IntelliJ)

Code style is driven by a workspace-level `.editorconfig`. The file lives at the root of this repo and must be
copied **one level above** all cloned service repositories so IntelliJ finds it as the workspace root:

```
your-workspace/          ← copy .editorconfig here
├── temporal-rift-bom/
├── game-service/
├── apis/
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
