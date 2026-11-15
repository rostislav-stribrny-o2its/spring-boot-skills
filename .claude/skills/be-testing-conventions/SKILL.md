---
name: be-testing-conventions
description: >
  Java/Spring backend: use when writing or reviewing tests — which test goes where, *Test
  (Surefire) vs *IT (Failsafe), the shared IT base classes AbstractIT / AbstractSchedulerIT with
  PostgreSQL (and MS SQL sources) in Testcontainers, requirement tags, naming, contract tests for
  every operation, ArchUnit, number boundaries, the security matrix and test data. Entry point; the
  be-spring-testing-* skills hold the techniques.
---

# Testing Conventions

This skill sets the conventions; the techniques are in the testing skills:
`be-spring-testing-strategy` (which test type), `be-spring-testing-fast` (context caching),
`be-spring-testing-web` (`MockMvcTester`, `RestTestClient`), `be-spring-testing-persistence`
(Spring Data JDBC, `JdbcClient`, Testcontainers), `be-spring-testing-security`,
`be-spring-testing-properties`. Where their examples differ from this skill, this skill wins
(names, base classes).

## `*Test` vs `*IT` — the primary split

| Suffix | Runs in | Contains | Needs |
|---|---|---|---|
| `*Test` | Surefire, `test` phase | plain unit tests (no Spring) and **slices with mocks** (`@WebMvcTest` + `@MockitoBean`) | JDK only — no Docker, no shared full context |
| `*IT` | Failsafe, `integration-test` + `verify` | everything that extends the shared base class (`@SpringBootTest`) and/or starts a container | Docker |

```bash
./mvnw -B verify              # both: *Test, then *IT (what CI runs)
./mvnw -B verify -DskipITs    # *Test only (property skipITs, Failsafe)
./mvnw -B test                # *Test only
```

- Surefire includes only `**/*Test.java`, Failsafe only `**/*IT.java` (plugin setup in
  `be-maven-reactor`). A class named `…Tests` or `…ITCase` silently never runs — rename it.
- A test that starts a container or extends the shared base class is an `*IT`, whatever it tests.
- **Testcontainers needs Docker on the CI runner** (or a compatible daemon). Without it the ITs fail;
  never mark them `disabledWithoutDocker` — a silently skipped suite is green and proves nothing.

## Which test where

| What | Test | Suffix |
|---|---|---|
| domain rules, value objects, mappers, converters, the token converter | plain JUnit 5 + AssertJ, no Spring | `*Test` |
| service orchestration with collaborators hard to set up | JUnit + Mockito | `*Test` |
| security rules of one API area (filter chain, `@PreAuthorize`) | `@WebMvcTest(<Tag>ApiController.class)` + `@Import(<Tag>ApiDelegateImpl.class, LibSecurityConfiguration.class)` | `*Test` |
| API operation (contract, status, Problem Details, validation, authorization) | `MockMvcTester` through the generated controller, the delegate and the real service, shared context | `*IT` |
| repository, `JdbcClient` query, aggregate mapping, constraint, `search_path` | through the real repository, shared context | `*IT` |
| flow across layers incl. transactions | through HTTP or the service, shared context | `*IT` |
| `scheduler-service` reading a source | MS SQL source in Testcontainers, shared context of that application | `*IT` |
| real Keycloak token through the decoder | one class (`be-spring-testing-security`) | `*IT` |
| architecture | ArchUnit | `*Test` |
| migrations | Flyway CI checks (empty DB, upgrade from last release) | `*IT` |

- Choose the mix by risk, not by a fixed pyramid; transactions, locking, streaming and SQL are tested
  against the real database, never with mocks.
- **Test strength and changing existing behaviour:** every functional/non-functional requirement test
  must fail when the target behaviour is broken (never write tautological tests that only echo mock
  returns). When modifying existing behaviour rather than adding a new feature, explicitly state
  **what must remain unchanged** and include a test that fails against the previous behaviour while
  keeping the existing regression suite green.
- **PostgreSQL 18 in Testcontainers** — the image of docker-compose; never H2.

## The shared IT base classes — one Spring context per application

Each application has one abstract base class; every `*IT` of that application extends it and adds
**nothing** that changes the context key (`be-spring-testing-fast`).

```java
// backend-service, src/test/java/app/backend/AbstractIT.java
@SpringBootTest(properties = {
    "spring.flyway.enabled=true",                                      // migrate at context start, as on LOCAL
    "APP_OIDC_ISSUER_URI=http://issuer.test/realms/app",           // project OIDC env prefix (from .env.example);
    "APP_OIDC_JWKS_URI=http://issuer.test/realms/app/protocol/openid-connect/certs",  // fakes never reach them
    "APP_OIDC_AUDIENCE=app-api",
    "APP_OIDC_CLIENT_ID=app-spa",
    "app.jobs.order-cleanup.cron=-"                                    // every job off (be-scheduling-shedlock)
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIT {
}

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection   // Boot wires the datasource; no @DynamicPropertySource for the database
    PostgreSQLContainer postgres() {   // Testcontainers 2: org.testcontainers.postgresql.PostgreSQLContainer
        return new PostgreSQLContainer(DockerImageName.parse(TestImages.POSTGRES))
            .withInitScript("db/test-admin.sql");   // schemas and roles, as the one-time DB admin script creates them
    }
}

final class TestImages {   // the one place in test code; same references (tag@digest) as docker-compose.yml
    static final String POSTGRES = "postgres:18.6-trixie@sha256:<digest from docker-compose.yml>";
    static final String KEYCLOAK = "quay.io/keycloak/keycloak:<tag@digest from docker-compose.yml>";
    static final String KEYCLOAK_CONFIG_CLI = "adorsys/keycloak-config-cli:<tag@digest from docker-compose.yml>";
    // scheduler-service: MSSQL = the SQL Server image matching the sources (not in docker-compose)
    private TestImages() { }
}
```

- `TestImages` is the only place in test code that names an image; a change of the image in
  `docker-compose.yml` changes it in the same pull request.
- `db/test-admin.sql` repeats the statements of the DB admin script (schemas, owner and runtime
  roles) with test passwords; Flyway migrates the container at context start, so ITs run against
  the real schema with the real grants and `search_path` (`db-flyway-migrations`).
- **Every new `@MockitoBean`, `properties`, profile or `@DynamicPropertySource` in a subclass is a new
  context** (and a new container). Shared mocks — e.g. the mail sender (`@MockitoBean JavaMailSender`)
  for e-mail over SMTP — and properties go into the base class; the one deliberate exception is the
  Keycloak IT (`be-spring-testing-security`), documented in that class.
- Reset data in `@BeforeEach` (child tables before parents), not by restarting the context;
  `@DirtiesContext` is not used.
- No `withReuse(true)` in CI; containers are cleaned up by Testcontainers at JVM exit.

**`scheduler-service`** has `AbstractSchedulerIT`: `@SpringBootTest` with its own job crons set to
`-`, `@Import(SchedulerTestcontainersConfiguration.class)`, **no** `@AutoConfigureMockMvc` (it has
no API). Its configuration adds the source next to PostgreSQL:

```java
@Bean
MSSQLServerContainer sourceDb() {   // org.testcontainers:testcontainers-mssqlserver
    return new MSSQLServerContainer(DockerImageName.parse(TestImages.MSSQL)).acceptLicense();
}

@Bean   // registered once, in the shared configuration: one context for every IT
DynamicPropertyRegistrar sourceProperties(MSSQLServerContainer sourceDb) {
    return registry -> {
        registry.add("app.sources.main.url", sourceDb::getJdbcUrl);
        registry.add("app.sources.main.username", sourceDb::getUsername);
        registry.add("app.sources.main.password", sourceDb::getPassword);
    };
}
```

The test views are created from the view contracts (`be-scheduler-service`). Sources are JDBC, so
there is no WireMock in this application; should an HTTP integration ever appear, stub it once in
the base class the same way.

## Requirement tags

An acceptance or integration test that verifies a documented requirement carries its ID as a JUnit 5
tag; coverage requirement → test is built from the tags. Production code carries no requirement IDs.

```java
@Test
@Tag("F-REQ-00012")   // ID of the technical specification, exactly as written there
void confirmOrder_whenAllItemsAvailable_confirmsAndReserves() { ... }
```

## Naming

`methodOrOperation_condition_expectedOutcome` — e.g. `createOrder_withoutItems_returns422`.
Test class per production class or per API tag, with the suffix of its kind: `OrderMapperTest`,
`OrdersApiIT`, `OrderRepositoryIT`, `OrdersApiSecurityTest`.

## Contract tests — every operation

- Each operation of the contract has at least one test through the generated controller: success
  status and body shape, each declared error (Problem Details `type`, `status`), validation of the
  request, and authorization rows.
- An operation the delegate does not implement answers `501` and must fail a test, never slip through.
- Serialization of numbers: IDs are JSON numbers; `BigDecimal` keeps its scale.

## Number boundary tests

Round trip API → database → API with boundary values: `2^31`, `2^53−1` (largest allowed ID),
the maximum of each `numeric(p,s)` column, negative values, the full number of decimal places.
A value that would lose precision must fail loudly, never be rounded.

## Architecture tests

ArchUnit rules run as `*Test` (see `be-archunit-rules`): layer direction, no JPA, `@Transactional`
only on services, generated API types only in the API layer, `@PreAuthorize` on every delegate
method, persistence types never in the API, no lossy number conversions.

## Security regression matrix

For every protected operation (technique in `be-spring-testing-security`):
- anonymous → `401`; invalid or expired token → `401`;
- authenticated without the required authority → `403`;
- allowed caller → success, with the content asserted;
- data of another owner never appears in a response, checked with the real query;
- denied writes leave the database unchanged and trigger no external effect.

Exercise the real `SecurityFilterChain` and method security; do not disable filters or add a
permissive test configuration to get green tests.

## Test data

- Synthetic data only; no production copies in tests.
- Data is created by the test (or `@Sql` fixtures) and reset in `@BeforeEach`, child tables
  before parents. Reference data of lookup tables comes from the migrations.
- The fictitious data of LOCAL and DEV stacks (`db-flyway-migrations`) is not test data: ITs do not
  load it and do not rely on it.
- End-to-end tests against a running backend delete everything they created.

## Project-specific conventions

- Requirement tags are the IDs of the technical specification: `F-<CODE>-NNNNN` (functional, e.g.
  `F-REQ-00012`) or `N-<AREA>-NNNNN` (non-functional), five digits with leading zeros, exactly as
  written there; IDs of change requests never appear in code, tests or commits.
- Test persons use personal numbers from the reserved test range, never real ones. The range is
  open (decided by the architecture owner; to be written in `docs/design/database/` together with
  the Keycloak test accounts); until then use numbers starting with `9000` and say so in the PR.
- Add the application's rows of the security matrix from `be-spring-testing-security` (unpaired account,
  suspension, role administration).

## Dependencies (test scope)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId> <!-- Testcontainers 2 module name -->
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <scope>test</scope>
</dependency>
<dependency>  <!-- scheduler-service only -->
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-mssqlserver</artifactId>
    <scope>test</scope>
</dependency>
```

Plus the Boot 4 test starter of each technology (`spring-boot-starter-webmvc-test`,
`spring-boot-starter-security-test`, …); they are self-contained. Versions not managed by Boot go
into the parent POM (`be-maven-reactor`).

## Gotchas
- Agent names a test `…Tests` or puts a container into a `*Test` — `*Test` = no Docker, `*IT` = base class/containers
- Agent uses a meta-annotation (`@AppTest`) or a bare `@SpringBootTest` on an IT — extend `AbstractIT` / `AbstractSchedulerIT`
- Agent marks ITs `disabledWithoutDocker` — CI must fail without Docker, not skip
- Agent uses H2 or an embedded database — PostgreSQL 18 in Testcontainers
- Agent uses `@MockBean` — removed in Boot 4; `@MockitoBean` / `@MockitoSpyBean`
- Agent adds `@MockitoBean`, `properties` or `@DynamicPropertySource` in an IT subclass — new context; move it to the base class
- Agent stubs an MS SQL source with WireMock or a mock — MS SQL in Testcontainers
- Agent writes a second image tag next to `TestImages` — one constant, equal to docker-compose
- Agent uses `@DataJpaTest` or JPA test utilities — the base has no JPA
- Agent uses `TestRestTemplate` by habit — `RestTestClient` (`@AutoConfigureRestTestClient`) or `MockMvcTester`
- Agent signs its own JWTs in tests of a resource server — fakes built by the real converter, one Keycloak IT for real tokens
- Agent asserts only the status — also the body, the persisted state and the absence of side effects
- Agent puts requirement IDs into production code — only `@Tag` on tests
- Agent leaves an operation without a contract test — every operation, every declared error
- Agent tests numbers only with small values — include the boundary values
- Agent uses `org.testcontainers:postgresql` (Testcontainers 1) — Boot 4 manages `testcontainers-postgresql`

Not yet verified by a build in this repository (init script with `@ServiceConnection`, image with
digest in `PostgreSQLContainer`, `MSSQLServerContainer` of Testcontainers 2).

## Provenance

Rewritten for this application's Java base (JDK 25, Spring Boot 4, contract-first API, Spring Data JDBC, PostgreSQL 18).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/testing-pyramid` (security regression matrix, naming, Testcontainers 2 dependencies), MIT License,
© Rrezart Prebreza (see `LICENSE.txt`). Techniques are delegated to the spring-testing-* skills from
[Kehrlann/spring-boot-testing-skills](https://github.com/Kehrlann/spring-boot-testing-skills) (Apache-2.0).
Changes in this version: `*Test`/`*IT` split, shared IT base classes with `@ServiceConnection`, MS SQL sources in
Testcontainers for `scheduler-service`. Project-specific conventions: section "Project-specific conventions".
