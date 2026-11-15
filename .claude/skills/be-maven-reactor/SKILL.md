---
name: be-maven-reactor
description: >
  Java/Spring backend: use when creating or changing the Maven build in backend/ - the reactor and
  parent POM (JDK 25, Spring Boot 4, versions in one place), modules api-model, api-spec, lib-core,
  lib-db, lib-web, lib-security, backend-service, scheduler-service, what lives in which library,
  composition via @Import, annotation processors, Boot 4 starters and what ./mvnw verify runs.
---

# Maven Reactor

The whole Java backend is **one Maven reactor in `backend/`** with **one parent POM** that holds
every version (ADR-00002, point 2). One command builds and tests everything in dependency order:

```
./mvnw -B -ntp verify          # the full gate, as CI runs it (unit + integration tests; needs Docker)
./mvnw -B test                 # the fast local loop: unit tests and slices, no Docker
```

Order inside the reactor: API model -> exported spec -> shared libraries -> applications.
`specs/` (repository root) is **not** a module (ADR-00006); the build writes into it.

## 1. Layout

```
backend/                          reactor root: pom.xml (packaging pom), .mvn/, mvnw, lombok.config
├── pom.xml                       parent POM: versions, pluginManagement, test + coverage setup
├── openapi-generator.json        the one locked generator configuration (be-api-contract-first)
├── openapi-templates-customization/JavaSpring/   controlled generator template changes
├── api-model/                    typed API model: records, enums, endpoint interfaces; no logic
├── api-spec/                     small Spring Boot app (model + springdoc), started at build to export specs/
├── lib-core/                     app.lib.core     - no web: mapping config, JSON helpers, Clock
├── lib-db/                       app.lib.db       - JdbcClient helpers (row mappers, pages)
├── lib-web/                      app.lib.web      - servlet pieces: correlation filter, global problem handler
├── lib-security/                 app.lib.security - resource server, roles (imports lib-web)
├── integration-contract/         plain Java row types + enums of the tables both applications share; no Spring
├── backend-service/              application: REST API (generated from the spec), business logic, its jobs
└── scheduler-service/            application: reads source systems on a schedule into schema integration; no public API
```

- Templates: `templates/parent-pom.xml`, `templates/lib-module-pom.xml`,
  `templates/app-module-pom.xml`. Copy, then adapt; keep the structure.
- Add a library only when two applications need the same code, or when a cross-cutting concern
  deserves one home. Code used by `backend-service` alone stays a package there (example: the
  audit log is `app.backend.audit` with its own table, not a `lib-audit`); a library for it is
  justified only once `scheduler-service` needs the same code. A new library is imported (`@Import`
  of its entry configuration) by exactly the applications that use it.
- A reference feature module `user` lives inside `backend-service` as package `app.backend.user`
  (a package, not a Maven module). New features follow it.

### What lives where

| Library | Contains | Imported by |
|---|---|---|
| `lib-core` | `CentralMapperConfig`, `CommonMappings`, `JsonMappings` (`be-mapstruct-dto-layers`), a `Clock` bean; **no servlet, Spring MVC or Security types** (ArchUnit `libCoreIsWebFree`) | both applications (through the libraries above it) |
| `lib-db` | `PrefixedRowMapper`, `PageResult`, `PageSpec` (`be-jdbc-client-reads`) | both applications |
| `lib-web` | `CorrelationIdFilter` (`be-correlation-logging`), `GlobalProblemHandler`, `ProblemException` (`be-problem-details-rfc9457`) | `backend-service` only (via `lib-security`) |
| `lib-security` | `LibSecurityConfiguration`, converter, `Roles` (`be-oauth2-resource-server`) | `backend-service` only |

`scheduler-service` imports `lib-core` and `lib-db`, never `lib-web` or `lib-security`; it needs
no Spring MVC.

## 2. Module roles and dependency rules

| Module | May depend on | Must not depend on |
|---|---|---|
| `api-model` | `spring-web` (mapping annotations only), `jakarta.validation-api`, `swagger-annotations-jakarta`, Jackson annotations | any Boot starter, `lib-*`, applications |
| `api-spec` | `api-model`, `spring-boot-starter-webmvc`, springdoc (`…-webmvc-api`) | `lib-*`, applications, database drivers |
| `lib-core`, `lib-db` | lower `lib-*`, Boot starters without web | `api-model`, `api-spec`, applications, `spring-boot-starter-webmvc` |
| `lib-web`, `lib-security` | `lib-core`, `lib-web` (for `lib-security`), `spring-boot-starter-webmvc`, the starters they need | `api-model`, `api-spec`, applications |
| a new `lib-<topic>` | `lib-core` always; `lib-db` if it uses JDBC; `lib-web` only if it needs servlet types (then it is web, and `scheduler-service` cannot import it) | `api-model`, `api-spec`, applications, `integration-contract` |
| `integration-contract` | JDK only | Spring, `lib-*`, applications |
| `backend-service` | `lib-*`, `integration-contract`; `api-spec` only as a **build-order** dependency (below) | `api-model` at compile scope, `scheduler-service` |
| `scheduler-service` | `lib-core`, `lib-db`, `integration-contract` | `lib-web`, `lib-security`, `api-model`, `api-spec`, `backend-service` |

- **No application depends on another application.** Shared code goes to a `lib-*` module.
- **The backend implements code generated from the committed spec, never the model interfaces**
  (ADR-00006). So `backend-service` does not see `api-model` classes.
- **Build order without classpath leakage:** `backend-service` declares `api-spec` with
  `<scope>provided</scope>` and a wildcard exclusion `*:*`. Maven then builds (and exports) the spec
  before generating code from it, also with `-pl backend-service -am` and `-T`. ArchUnit forbids
  any use of `app.api..` and `app.apispec..` from `app.backend..` (`be-archunit-rules`).
- Libraries are versioned with the reactor (`${project.version}` in the parent's
  `dependencyManagement`); third-party versions come from the Spring Boot BOM, or are pinned in the
  parent when Boot does not manage them.
- `integration-contract` keeps its ADR name; it holds only `...Row` records and enums of the
  shared tables, has no `@Configuration` and no Spring dependency (details: be-scheduler-service).
- **Adding a `lib-<topic>`:** `<module>` after the libraries it depends on and before
  `integration-contract` and the applications; a `dependencyManagement` entry
  `app:lib-<topic>:${project.version}`; a row in "What lives where"; its package in the
  library rules of `be-archunit-rules` (and in the web-free rule if it is web-free).

## 3. Packages

| Code | Package |
|---|---|
| API model | `app.api..` |
| spec export app | `app.apispec..` |
| shared library | `app.lib.<topic>..` |
| integration table contract | `app.integration.contract..` |
| backend application | `app.backend..` (main class `app.backend.BackendServiceApplication`) |
| scheduler application | `app.scheduler..` (main class `app.scheduler.SchedulerServiceApplication`) |
| code generated from the spec | `app.backend.api.generated.<area>..` (in `target/`, never committed) |

**Never put a main class directly in `app`.** `@SpringBootApplication` scans its own package
downwards; in `app` it would silently pick up every `app.lib.*` bean and the explicit composition
below would stop meaning anything.

## 4. Shared libraries: one entry configuration, explicit hierarchical `@Import`

This is the only home of the composition pattern; other skills refer here. Each `lib-<topic>` has
exactly **one entry `@Configuration`** in its root package. It scans its own package (the class is
its own marker), registers its own properties and imports the libraries it builds on:

```java
package app.lib.web;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = LibWebConfiguration.class)
@Import(LibCoreConfiguration.class)
public class LibWebConfiguration {
}
```

```java
package app.backend;

@SpringBootApplication                       // scans app.backend.. only
@ConfigurationPropertiesScan                  // the application's own @ConfigurationProperties records
@Import({LibDbConfiguration.class, LibSecurityConfiguration.class})   // + lib-web, lib-core through the hierarchy
public class BackendServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BackendServiceApplication.class, args);
    }
}
```

`SchedulerServiceApplication` imports `LibDbConfiguration` (and through it `LibCoreConfiguration`).

- Importing an upper library brings the lower ones with it; importing one twice is harmless.
- **Properties:** one prefix `app.` for every property of our own: libraries `app.<topic>.*`,
  registered from the entry configuration with `@EnableConfigurationProperties`; applications
  `app.<area>.*`, found by `@ConfigurationPropertiesScan` on the main class. Environment variables
  are the upper-case form (`APP_…`); the security and database variables keep the names listed in
  `backend/.env.example` (example: `<PREFIX>_OIDC_*`, `DB_*`).
- A bean that an application may replace is declared with `@ConditionalOnMissingBean`; prefer this
  over profiles.
- **No library auto-configuration** (`META-INF/spring/…AutoConfiguration.imports`): the libraries
  live in this repository and are composed explicitly. Spring Boot's own auto-configuration stays on.

## 5. Applications: the module decides the role

- `backend-service` and `scheduler-service` are separate runnable modules, each with its own
  `spring-boot-maven-plugin` and container image. **What an application does is decided by its
  module, not by a Spring profile or a property** (ADR-00002, point 2).
- Spring profiles exist **only for environment behaviour**. A profile or property that switches a
  role on or off is a finding.
- Scheduled jobs use `@Scheduled` with a ShedLock lock in PostgreSQL, in a pool separate from REST
  handling (ADR-00002, point 4; `be-scheduling-shedlock`).

## 6. Parent POM - rules

See `templates/parent-pom.xml` (complete skeleton).

- Parent is `spring-boot-starter-parent` with a **literal** version (template: `4.1.2`; properties
  are not resolved there). Minor Boot upgrades need no ADR; a new major Java or Boot version does.
- `<java.version>25</java.version>` only; it drives `maven.compiler.release`. Never set
  `source`/`target`. Java versions: LTS only.
- Every version not managed by the Boot BOM is a property in the parent. **Priority: the
  repository's existing `backend/pom.xml` always wins**; the template's literal versions (those of
  the stack's reference implementation) are used only for entries the repository does not have yet.
  A version that neither has: the latest release on Maven Central whose release notes state support
  for JDK 25 / Spring Boot 4, as a literal, never a range; without access to Maven Central, ask.
  Modules never carry versions.
- `<dependencyManagement>` for everything; parent `<dependencies>` normally empty.
- `spring-boot-maven-plugin` only in `backend-service`, `scheduler-service` and `api-spec` (start/stop
  only, `repackage` off), never in the parent's `<plugins>`.
- `repo.root` = `${maven.multiModuleProjectDirectory}/..` (`backend/.mvn/` makes it the reactor root
  even with `-pl`); every path to `specs/` is built from it.
- Enforcer: `requireJavaVersion [25,26)`, `requireMavenVersion`, `bannedDependencies` for
  JPA/Hibernate, broker and Redis clients (ADR-00002). Spotless (`palantirJavaFormat`,
  `removeUnusedImports`) `check` in the parent's `<plugins>`. Profiles `sast` (SpotBugs +
  FindSecBugs) and `sca` (CycloneDX SBOM), activated by `SAST_ENABLED=true` / `SCA_ENABLED=true`
  (ADR-00009 point 2). All blocks are in the template.

## 7. Annotation processors and Lombok

Order **Lombok -> `lombok-mapstruct-binding` -> MapStruct** in the parent's `maven-compiler-plugin`
`annotationProcessorPaths` (already in `templates/parent-pom.xml`); no `-Amapstruct.*` arguments;
`lombok` `provided` where used. The one source of this setup, which `be-lombok` and
`be-mapstruct-dto-layers` refer to: [annotation-processors.md](annotation-processors.md).

## 8. Tests in the build

| Kind | Name | Plugin / phase | Runs |
|---|---|---|---|
| unit tests and slices with mocks (`@WebMvcTest`, …): no shared full context, no Docker | `*Test` | Surefire, `test` | always |
| shared Spring context (`@SpringBootTest` base class) and/or Testcontainers | `*IT` | Failsafe, `integration-test` + `verify` | `verify`, unless `-DskipITs` |

- **Where ITs run.** No CI-only switch: `skipITs=false`, so every `verify` runs the ITs — CI runs
  `backend/scripts/db.sh migrate` then `./mvnw -B verify` (with `SAST_ENABLED`/`SCA_ENABLED`).
  Locally the default loop is `./mvnw -B test` (no Docker, ADR-00009 point 1); without Docker use
  `-DskipITs` and leave the ITs to CI (the spec export still runs).
- Surefire includes only `**/*Test.java`, Failsafe only `**/*IT.java` (explicit, not the plugin
  defaults). Failsafe's `excludedGroups` comes from the property `it.excludedGroups` (empty by
  default, so CI runs everything). **IT groups:** an IT that needs extra infrastructure beyond
  PostgreSQL carries a lower-case JUnit tag naming it — today only `@Tag("keycloak")` on the Keycloak
  IT; locally `-Dit.excludedGroups=keycloak` (comma-separated for more) skips it. Other `@Tag`s on
  test classes are traceability labels, not groups.
- Unit coverage gate: JaCoCo `check` in `test` with `jacoco.unit.line.min` (0.80) over business
  logic (ADR-00009 point 4); modules without business logic set `jacoco.unit.skip=true`.
- JaCoCo: `prepare-agent` writes `surefireArgLine`, `prepare-agent-integration` writes
  `failsafeArgLine` (separate properties, so each plugin gets one agent); `merge` after the
  integration tests and `report` in `verify` produce one report per module
  (`backend/<module>/target/site/jacoco/jacoco.xml`) for Sonar.
- Without Docker, ITs fail - they do not silently skip.
- Test starters: `spring-boot-starter-<technology>-test` (Boot 4); they are self-contained.

## 9. What `./mvnw -B -ntp verify` does, in order

`api-model` (compile, ArchUnit over the model) → `api-spec` (start, export `specs/…`, stop) →
`lib-*`, `integration-contract` → `backend-service` (generate from the just re-exported spec,
compile, tests, ArchUnit, ITs) → `scheduler-service` → JaCoCo reports; CI then checks
`git diff --exit-code -- specs/` and runs the spec guards.

### Local incremental verification in three stages

Never re-run the whole reactor on every fix: (1) inner loop on the touched test or module, (2) a
per-item check of what depends on the change, (3) one final gate after all items —
`./mvnw -q spotless:apply && SAST_ENABLED=true SCA_ENABLED=true ./mvnw -B verify` (+
`backend/scripts/api-guards.sh` after a model change). Commands: [local-verification.md](local-verification.md).

## 10. Spring Boot 4 starters

Which starter or artifact goes into which module (web, security, JDBC, Flyway, actuator, tracing,
drivers, ShedLock, Testcontainers): [starters.md](starters.md). In short: `-webmvc` not `-web`,
`-aspectj` not `-aop`, `-data-jdbc` never `-data-jpa`, Flyway needs its starter.

- Jackson 3 (`tools.jackson.*`) is the JSON stack; do not add Jackson 2 artifacts. The one
  exception is `api-spec`, where swagger-core brings Jackson 2 (`be-api-contract-first`).
- Verified in the stack's reference implementation: the `provided` + `*:*` exclusion on `api-spec`,
  the JaCoCo setup with Boot 4.1 and JDK 25, the `sast`/`sca` profiles.

## Gotchas

- Agent puts the main class in package `app` - it then scans every library; use `app.backend` / `app.scheduler`
- Agent selects the application role with a Spring profile or property - the role is the module
- Agent makes `backend-service` depend on `api-model` - the backend implements generated code only
- Agent makes one application depend on another - move shared code to `lib-*`
- Agent puts a servlet filter or `@RestControllerAdvice` into `lib-core` - `lib-web`; `scheduler-service` has no web layer
- Agent imports `lib-web` or `lib-security` into `scheduler-service` - it imports `lib-core` and `lib-db` only
- Agent registers a library through auto-configuration or a wide `@ComponentScan("app")` - one entry `@Configuration`, explicit `@Import`
- Agent sets `source`/`target` or `java.version` 21 - JDK 25 via `java.version`
- Agent adds a version in a module POM, leaves a plugin unpinned or overwrites the repository's versions with the template's - literal versions in the parent only; the existing `backend/pom.xml` wins
- Agent adds a profile that runs ITs "only in CI" - every `verify` runs them; locally `test` or `-DskipITs`
- Agent puts `spring-boot-maven-plugin` into the parent's `<plugins>` - only runnable modules
- Agent adds `spring-boot-starter-data-jpa`, Hibernate, a broker or Redis client - banned by the enforcer
- Agent configures Lombok + MapStruct without `lombok-mapstruct-binding` - mappers depend on processor order
- Agent adds only `flyway-core` - migrations never run; `spring-boot-starter-flyway` + `flyway-database-postgresql`
- Agent names an integration test `*Test` or a unit test `*IT` - the split decides where and when it runs
- Agent disables tests to make the build pass (`-DskipTests` in CI) - fix the cause

## Provenance

Rewritten for this application's Java base (JDK 25, Spring Boot 4, one reactor). Parent-POM
conventions and processor order derive from
[rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/multi-module-maven`, MIT License, (c) Rrezart Prebreza.
