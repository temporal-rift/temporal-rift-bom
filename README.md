# temporal-rift-bom

Parent BOM for Temporal Rift services. Provides unified dependency management, formatting (Spotless + Palantir)
and code quality (Checkstyle).

## Requirements

- JDK 26
- Maven 4.0.0-rc-7 or newer (Maven 4 GA once released). Every POM uses model version 4.1.0, which Maven 3 cannot
  read, and the BOM's enforcer rejects Maven 3 in every service that inherits it.

## Structure

```
temporal-rift-bom/
├── pom.xml                          ← parent for all Temporal Rift services; reactor root
├── build-tools/
│   ├── pom.xml                      ← Checkstyle rules jar
│   └── src/main/resources/
│       └── checkstyle.xml
└── asyncapi-codegen-maven-plugin/
    └── pom.xml                      ← AsyncAPI channel-contract generator (Maven 4 plugin API)
```

The root POM aggregates `build-tools` and `asyncapi-codegen-maven-plugin` as `<subprojects>` but is **not** their parent:
its own build uses both (Checkstyle rules and contract generation), so inheriting from it would be circular. The
reactor builds them first, so a single `mvn verify` at the root builds every artifact and runs the BOM's build against
the freshly built plugin. Each artifact keeps its own version.

## Publishing

The `main`-branch workflow (`publish.yml`) deploys, in one reactor build, every artifact whose version is not yet
in GitHub Packages, then tags the release. A BOM released together with a new plugin version therefore builds against
that plugin. Do not use local Maven publication to bridge a consumer to an unreleased BOM version.

Maven Central is promoted manually: run the `Promote to Maven Central` workflow (`promote-central.yml`) to deploy,
signed and in one bundled deployment, every artifact version Central does not yet have. Nothing else publishes to
Central.

### Consuming from GitHub Packages

GitHub Packages requires authentication even for public packages. Declare a `github` server with a token that has
`read:packages` in `~/.m2/settings.xml`, and a profile adding
`https://maven.pkg.github.com/temporal-rift/temporal-rift-bom` as both a repository and a plugin repository. In
GitHub Actions use `GITHUB_TOKEN` with `packages: read`.

## Usage in services

```xml

<parent>
    <groupId>io.github.temporal-rift</groupId>
    <artifactId>temporal-rift-bom</artifactId>
    <version>2.0.1</version>
</parent>
```

## What's included

| Category           | Plugin / Dependency                                                     |
|--------------------|-------------------------------------------------------------------------|
| Formatting         | Spotless + Palantir Java Format 2.99.0                                  |
| Code quality       | Checkstyle 14.1.0 (Google style, adapted)                               |
| Enforcement        | Maven Enforcer (Java 26, Maven 4.0.0-rc-7+)                             |
| Coverage           | JaCoCo (managed, opt-in per service)                                    |
| API generation     | OpenAPI Generator 7.25.0 (managed, opt-in)                              |
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

Generated AsyncAPI payload records carry Bean Validation annotations derived from supported schema constraints. Generated
contracts require the Jakarta Validation API and Hibernate Validator on the compile classpath; Spring Boot applications
can use `spring-boot-starter-validation`.

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

# Build and test every artifact
mvn verify
```
