# temporal-rift-bom

Parent BOM for Temporal Rift services. Provides unified dependency management, formatting (Spotless + Palantir) and code quality (Checkstyle).

## Structure

```
temporal-rift-bom/
├── pom.xml             ← parent for all Temporal Rift services
└── build-tools/
    ├── pom.xml         ← standalone jar, built separately
    └── src/main/resources/
        └── checkstyle.xml
```

`build-tools` is intentionally **not** a Maven reactor module of the root BOM,
because the root BOM's Checkstyle plugin depends on `build-tools` at resolution time.
Listing it as a module would create a circular plugin dependency.

## Build order

`build-tools` must be built and installed **before** the root BOM.

### First time / fresh checkout

```bash
# 1. Build and install build-tools locally
mvn -f build-tools/pom.xml install

# 2. Build and install root BOM
mvn install -Denforcer.skip=true
```

### Publish to GitHub Packages

```bash
mvn -f build-tools/pom.xml deploy -Pghrepo
mvn deploy -Pghrepo
```

## Usage in services

```xml
<parent>
  <groupId>com.temporalrift</groupId>
  <artifactId>temporal-rift-bom</artifactId>
  <version>1.0.0</version>
</parent>
```

## What's included

| Category | Plugin / Dependency |
|---|---|
| Formatting | Spotless + Palantir Java Format 2.90.0 |
| Code quality | Checkstyle 13.2.0 (Google style, adapted) |
| Enforcement | Maven Enforcer (Java 25, Maven 3.9.13+) |
| Coverage | JaCoCo (managed, opt-in per service) |
| API generation | OpenAPI Generator 7.20.0 (managed, opt-in) |
| Shared contracts | `domain-events:1.0.0` |

## Commands

```bash
# Format all Java files
mvn spotless:apply

# Check formatting and style
mvn validate
```
