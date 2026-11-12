---
name: be-maven-reactor
description: >
  Use when creating or changing the Maven build of a Spring Boot 4 backend: the single reactor
  with the API model, the generated spec and the runtime modules, the parent POM (JDK 25, Boot
  BOM, pinned plugins), module dependency rules, annotation processors (MapStruct, optional
  Lombok), Boot 4 modular starters and what runs in mvn verify.
---

# Maven Reactor

One repository, **one Maven reactor**, one `mvn verify` builds everything in the right order:
API model → generated spec → runtime modules. A change of the API is one pull request with the
model, the regenerated spec, the backend implementation and the frontend.

## Structure

```
<java-root>/                     ← parent POM (packaging pom), the reactor root
├── pom.xml
├── <module>-api-model/          ← typed API model (Api… classes, endpoint interfaces), no Spring runtime
├── <module>-api-spec/           ← exports the OpenAPI document (export approach: see api-contract-first, to be confirmed), runs the spec guards
├── backend/                     ← Spring Boot application: generated controllers, delegates, services, persistence
├── <shared-lib>/                ← optional: shared types without Spring and without business logic
└── <second-app>/                ← optional: a second Spring Boot application (e.g. an integration service)
specs/<app>/openapi/...          ← committed specs written by <module>-api-spec (see api-contract-first)
```

- Exactly one `<module>-api-model` + `<module>-api-spec` pair per module that exposes an
  interface; modules without an interface have none.
- A shared library holds only contract types (records, enums) that two applications really share;
  never Spring, never business logic.
- Moving the API model into its own repository later (published artifact) needs no code change;
  do it only when a second application consumes it.

## Dependency rules

| Module | May depend on | Must not depend on |
|---|---|---|
| `<module>-api-model` | swagger-core annotations, validation API | any runtime module, Spring Boot starters |
| `<module>-api-spec` | `<module>-api-model` (build-time) | runtime modules |
| runtime application | its `<module>-api-spec` (scope `provided`, so the reactor builds and checks the spec first), shared libraries | another application module |
| shared library | JDK, validation API | Spring, runtime modules |

The `provided` dependency on `<module>-api-spec` fixes the build order model → spec → runtime even
with `-T` or `-pl … -am`, so the spec guards run before code is generated from the spec. No cycles;
package-level rules are checked by ArchUnit.

## Parent POM

```xml
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.x.y</version>   <!-- literal: Maven resolves no ordinary properties here -->
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>app-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>core-api-model</module>
        <module>core-api-spec</module>
        <module>backend</module>
    </modules>

    <properties>
        <java.version>25</java.version>
        <specs.dir>${maven.multiModuleProjectDirectory}/specs</specs.dir> <!-- adjust to where specs/ sits -->
        <mapstruct.version>…</mapstruct.version>
        <openapi-generator.version>…</openapi-generator.version>
        <archunit.version>…</archunit.version>   <!-- archunit-junit5 is not in the Boot BOM: manage it here -->
    </properties>

    <dependencyManagement>
        <!-- inter-module artifacts with ${project.version}; third-party versions not managed by the Boot BOM -->
    </dependencyManagement>

    <build>
        <pluginManagement>
            <!-- every plugin version pinned here: compiler, surefire/failsafe, openapi-generator,
                 swagger export, enforcer, jacoco; modules only reference them -->
        </pluginManagement>
    </build>
</project>
```

- The reactor root has a `.mvn/` directory, so `maven.multiModuleProjectDirectory` is the reactor
  root even when Maven runs from a submodule (`-pl`); the path to `specs/` is defined once as
  `specs.dir` and every generator and export uses it.
- `java.version` 25 drives `maven.compiler.release` (via `spring-boot-starter-parent`); never set
  `source`/`target` separately.
- Dependency versions come from the Boot BOM; override one only with a written reason.
- `<dependencies>` in the parent only for test libraries every module needs; everything else in
  `<dependencyManagement>`.
- `spring-boot-maven-plugin` only in the runnable application modules, never in the parent.
- Maven Enforcer: built-in rules `requireJavaVersion` (25), `requireMavenVersion`,
  `dependencyConvergence` and `bannedDependencies` (JPA, Hibernate, `spring-boot-starter-data-jpa`;
  per module also the modules it must not depend on). Duplicate classes need the separate
  `extra-enforcer-rules` artifact (`banDuplicateClasses`).

## Annotation processors

MapStruct is always on. Lombok is an optional recipe of the base; when a project enables it, the
order is **Lombok → `lombok-mapstruct-binding` → MapStruct**. This configuration belongs in the
parent's `pluginManagement`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>   <!-- only with Lombok; version from the Boot BOM property -->
            <path><groupId>org.projectlombok</groupId><artifactId>lombok-mapstruct-binding</artifactId><version>${lombok-mapstruct-binding.version}</version></path> <!-- only with Lombok -->
            <path><groupId>org.mapstruct</groupId><artifactId>mapstruct-processor</artifactId><version>${mapstruct.version}</version></path>
        </annotationProcessorPaths>
        <compilerArgs>
            <arg>-Amapstruct.defaultComponentModel=spring</arg>
            <arg>-Amapstruct.unmappedTargetPolicy=ERROR</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

`typeConversionPolicy` is not a processor option: it is set in the shared `@MapperConfig` that
every mapper uses (see `be-backend-layers`).

## What `mvn verify` runs

1. API model: compile + ArchUnit rules over the model.
2. Spec module: export the spec, lint it (G1), compare with the committed file (G5),
   `oasdiff breaking` against the last release (G4).
3. Runtime modules: generate from the spec, compile (G2), unit tests, ArchUnit rules over the
   runtime code, integration tests with PostgreSQL in Testcontainers (Failsafe), Flyway checks.
4. Coverage report and SAST (Sonar) in CI.

## Boot 4 modular starters

- One starter per technology and module; renamed starters: `spring-boot-starter-web` →
  `spring-boot-starter-webmvc`, OAuth2 starters with the `security-` prefix
  (`spring-boot-starter-security-oauth2-resource-server`), `spring-boot-starter-aop` →
  `spring-boot-starter-aspectj`.
- Persistence: `spring-boot-starter-data-jdbc` (Spring Data JDBC + `JdbcClient`), never
  `spring-boot-starter-data-jpa`.
- Flyway does not arrive transitively: `spring-boot-starter-flyway` + `flyway-database-postgresql`
  in the module that owns the migrations.
- Test starters follow `spring-boot-starter-<technology>-test` and are self-contained.
- `spring-boot-starter-classic` / `-test-classic` are transitional only — not in new modules.
- Optional dependencies are excluded from the repackaged jar unless `includeOptional` is set
  (upstream note; verify against the Boot 4 migration guide when it matters).

## Opereta

- **Modules** (decided by R. Stříbrný, OQ-014 J1): `api-model`, `api-spec`, `backend`, `integration-contract`,
  `integration-service` — one reactor with a common parent POM.
  - `backend` — the Spring Boot application with all business logic;
  - `integration-contract` — shared library: only the contract types of the integration (`…Row`
    records of the transport and control tables, enums), no Spring, no business logic;
  - `integration-service` — a second Spring Boot application (MS SQL and PostgreSQL drivers,
    ShedLock, configuration from the environment), no public route.
- **Interfaces:** only `backend` has an interface, and it is `public`; its module code in the spec
  path is `be`: `specs/opereta/openapi/public/be/be-service-public.json`. `integration-service`
  exposes no API. `integration-service` has its own test suite and shared context.
- **Lombok is enabled** in Opereta (processor order Lombok → `lombok-mapstruct-binding` → MapStruct).
- Where the reactor root sits in the repository follows the Opereta template of Elysium
  (Java backend in `backend/`); confirm the exact layout against the template in `main` before
  creating modules.

## Gotchas
- Agent creates separate builds or repositories for the API model — one reactor, one `mvn verify`
- Agent adds a hexagonal split (`domain`/`application`/`infrastructure` modules) — layers are packages inside the application module
- Agent puts Spring or business logic into a shared library — contract types only
- Agent sets `<java.version>21</java.version>` or `source`/`target` — JDK 25 via `java.version`
- Agent adds `spring-boot-starter-data-jpa` or Hibernate — Spring Data JDBC only
- Agent configures Lombok and MapStruct without `lombok-mapstruct-binding` — mappers then depend on processor order
- Agent puts `spring-boot-maven-plugin` in the parent POM — only in runnable modules
- Agent adds `<dependencies>` in the parent instead of `<dependencyManagement>` — every module gets them
- Agent leaves plugin versions unpinned — pin them in `pluginManagement`
- Agent overrides Boot-managed versions without a reason — prefer the BOM
- Agent adds `spring-boot-starter-test` next to a `…-test` starter — Boot 4 test starters are self-contained
- Agent adds bare `flyway-core` — use `spring-boot-starter-flyway`

## Provenance

Rewritten for the Elysium Java base (JDK 25, Spring Boot 4, one reactor: API model → spec → runtime modules).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/multi-module-maven` (parent POM conventions, processor order, Boot 4 modular starters), MIT License,
© Rrezart Prebreza. The original is kept in `_unused/skills/spring-boot-4/multi-module-maven`.
Opereta layer: section "Opereta".
