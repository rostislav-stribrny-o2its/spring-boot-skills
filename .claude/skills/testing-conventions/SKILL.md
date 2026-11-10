---
name: testing-conventions
description: >
  Use when writing or reviewing tests of a Spring Boot 4 backend on the Elysium Java base:
  which test goes where, requirement tags, naming, one shared Spring context with PostgreSQL
  in Testcontainers, contract tests for every API operation, ArchUnit, number boundary tests,
  the security regression matrix and test data. Entry point; the spring-testing-* skills hold
  the techniques.
---

# Testing Conventions

This skill sets the conventions; the techniques are in the testing skills:
`spring-testing-strategy` (which test type), `spring-testing-fast` (context caching),
`spring-testing-web` (`MockMvcTester`, `RestTestClient`), `spring-testing-persistence`
(Spring Data JDBC, `JdbcClient`, Testcontainers), `spring-testing-security`,
`spring-testing-http-client`, `spring-testing-properties`.

## Which test where

| What | Test | Context |
|---|---|---|
| domain rules, value objects, mappers | plain JUnit 5 + AssertJ, no Spring | none |
| service orchestration with collaborators that are hard to set up | JUnit + Mockito | none |
| API operation (contract, status, Problem Details, validation, authorization) | `MockMvcTester` against the generated controller + real delegate | shared `@SpringBootTest` |
| repository, `JdbcClient` query, aggregate mapping, constraint | through the real repository | shared `@SpringBootTest` + Testcontainers |
| flow across layers incl. transactions | through HTTP or the service | shared `@SpringBootTest` + Testcontainers |
| architecture | ArchUnit | none |
| migrations | Flyway CI checks (empty DB, upgrade from last release) | Testcontainers |

- **One shared Spring context** per application (each Spring Boot application of the reactor has
  its own test suite and its own shared context) (a meta-annotation importing the Testcontainers
  configuration); slices only in a large, slow-starting application. Every new
  `@MockitoBean`, `properties` or profile combination costs a new context.
- **PostgreSQL 18 in Testcontainers** (`postgres:18`, same major version as production) —
  never H2.
- Choose the mix by risk, not by a fixed pyramid percentage; transactions, locking, streaming
  and SQL are tested against the real database, not with mocks.

## Requirement tags

An acceptance or integration test that verifies a documented requirement carries its ID (in the
format of the project's documentation) as a JUnit 5 tag; coverage requirement → test is built from the tags. Production code carries no
requirement IDs.

```java
@Test
@Tag("REQ-0012")    // the project's requirement ID
void confirmOrder_whenAllItemsAvailable_confirmsAndReserves() { ... }
```

## Naming

`methodOrOperation_condition_expectedOutcome` — e.g. `createOrder_withoutItems_returns400`.
Test class per production class or per API tag (`OrdersApiTests`).

## Contract tests — every operation

- Each operation of the contract has at least one test through the generated controller: success
  status and body shape, each declared error (Problem Details `type`, `status`), validation of the
  request, and authorization rows.
- With `delegatePattern` an operation the delegate does not implement answers `501` — the
  contract tests catch it.
- Serialization of numbers: IDs are JSON numbers; `BigDecimal` keeps its scale.

## Number boundary tests

Round trip API → database → API with boundary values: `2^31`, `2^53−1` (largest allowed ID),
the maximum of each `numeric(p,s)` column, negative values, the full number of decimal places.
A value that would lose precision must fail loudly, never be rounded.

## Architecture tests

ArchUnit rules run in `mvn verify` (see `archunit-rules`): layer direction, no JPA, `@Transactional`
only on services, generated API types only in the API layer, persistence types never in the API,
no lossy number conversions, streams closed.

## Security regression matrix

For every protected operation (technique in `spring-testing-security`):
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
- End-to-end tests against a running backend delete everything they created.

## Opereta

- Requirement tags are the IDs of the technical specification: `F-<CODE>-NNNNN` (functional, e.g.
  `F-REQ-00012`) or `N-<AREA>-NNNNN` (non-functional), five digits with leading zeros, exactly as
  written there; IDs of change requests never appear in code, tests
  or commits.
- Test persons use personal numbers from the reserved range (`OPERETA_TEST_PERSONAL_NUMBER_RANGE`);
  never real personal numbers.
- Add the Opereta rows of the security matrix from `spring-testing-security` (unpaired account,
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
```

Plus the Boot 4 test starter of each technology (`spring-boot-starter-webmvc-test`,
`spring-boot-starter-security-test`, …); they are self-contained.

## Gotchas
- Agent uses H2 or an embedded database — PostgreSQL 18 in Testcontainers
- Agent uses `@MockBean` — removed in Boot 4; `@MockitoBean` / `@MockitoSpyBean`
- Agent creates a new Spring context per test class with its own mocks or properties — reuse the shared context
- Agent uses `@DataJpaTest` or JPA test utilities — the base has no JPA
- Agent uses `TestRestTemplate` by habit — `RestTestClient` (`@AutoConfigureRestTestClient`) or `MockMvcTester`
- Agent signs its own JWTs in tests of a resource server — use `jwt()` and test the converter separately
- Agent asserts only the status — also the body, the persisted state and the absence of side effects
- Agent puts requirement IDs into production code — only `@Tag` on tests
- Agent leaves an operation without a contract test — every operation, every declared error
- Agent tests numbers only with small values — include the boundary values
- Agent uses `org.testcontainers:postgresql` (Testcontainers 1) — Boot 4 manages `testcontainers-postgresql`

## Provenance

Rewritten for the Elysium Java base (JDK 25, Spring Boot 4, contract-first API, Spring Data JDBC, PostgreSQL 18).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/testing-pyramid` (security regression matrix, naming, Testcontainers 2 dependencies), MIT License,
© Rrezart Prebreza. The original is kept in `_unused/skills/spring-boot-4/testing-pyramid`. Techniques are delegated to
the spring-testing-* skills from [Kehrlann/spring-boot-testing-skills](https://github.com/Kehrlann/spring-boot-testing-skills) (Apache-2.0).
Opereta layer: section "Opereta".
