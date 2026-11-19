---
name: be-backend-layers
description: >
  Java/Spring backend: use when adding or moving a class between the layers of a domain module in
  backend-service — the API delegate implementing the generated …ApiDelegate, services and their
  transaction boundary, write repositories (Spring Data JDBC) and read repositories (JdbcClient),
  the three object layers with MapStruct, packages per module and the dependency direction
  ArchUnit enforces. Maven modules and lib-* composition: be-maven-reactor.
---

# Backend Layers

The backend is **layered**, one predictable pattern in every domain module (ADR-STACK-00005 point 6:
the same three object layers in every domain module). Each layer has one job; ArchUnit fails the build when a
dependency points the wrong way (`be-archunit-rules`).

```
generated …ApiController  ← from the contract (be-api-contract-first); never edited, never committed
      ↓ calls the generated …ApiDelegate interface
API delegate              ← …ApiDelegateImpl (@Component): …ApiMapper (MapStruct) + ONE service call
      ↓ domain commands / domain records          ↑ domain records or …Row read models
service                   ← @Service: ALL business logic; @Transactional = one service operation
      ↓ domain records                             ↓ read parameters
write repository          read repository
(Spring Data JDBC         (JdbcClient, SQL in text blocks,
 aggregate + …Persistence  returns …Row / page / Stream<…Row>)
 Mapper)
      ↓                                            ↓
PostgreSQL 18             ← schema from Flyway only; integrity constraints, no business logic
```

## Applications and libraries

- **`backend-service`** — the REST API for the frontend, all business logic, its own scheduled jobs.
- **`scheduler-service`** (the template's sample of a timer, ADR-STACK-00001 point 2, ADR-STACK-00007 point 2) —
  no domain model, no layers of this skill (see `be-scheduler-service`). Applications that need no extra
  timer executable may drop it.
- **Role is decided by the Maven module, never by a Spring profile.** Profiles only select
  environment behaviour (LOCAL, DEV, TEST, PROD). A profile that switches a role's classes on or
  off is a review finding (ADR-STACK-00001 point 2).
- **Shared libraries `lib-<topic>`** (`lib-core`, `lib-db`, `lib-web`, `lib-security`) carry code
  all applications (or `backend-service` alone when no scheduler) or several modules need; each has one
  entry `@Configuration`, composed by the application with explicit `@Import`. Module list, what lives in
  which library, the entry configuration and main-class placement: `be-maven-reactor` (the only home of
  that pattern).

## Packages

Package by **domain module** first, then by layer:

```
app.backend                                   main class, application wiring (@Import)
app.backend.config                            @Configuration of this application only
app.backend.api.generated.<area>              generated …Api, …ApiController, …ApiDelegate and …Dto
                                              (target/, not committed; be-api-contract-first)
app.backend.<module>.api                      …ApiDelegateImpl (API delegates), …ApiMapper
app.backend.<module>.service                  @Service, commands, domain exceptions
app.backend.<module>.domain                   domain records, value objects, enums
app.backend.<module>.persistence              write repositories, …Entity, …PersistenceMapper,
                                              Spring Data interfaces (package-private),
                                              read repositories, …Row read models
app.lib.<topic>..                             shared library code (one entry @Configuration)
```

A module is a package of code, not a database schema: the backend has one schema
(ADR-STACK-00005 point 12; migrations run as the schema owner, the application at runtime under a role
that may not change the schema). SQL and `@Table` names of that schema are unqualified (the connection's
`search_path` is the backend schema).

## Three object layers (ADR-STACK-00005 point 6)

| Layer | Naming | Written by | Rules |
|---|---|---|---|
| API | `OrderDto`, `CreateOrderDto` | generator | only in `..api..`; never hand-written, never edited |
| Domain | `Order`, `OrderStatus`, `CreateOrderCommand` | you | `record`s without persistence annotations; no Spring Data, no Jackson, no generated types |
| Persistence | `OrderEntity` (aggregate root), `OrderSummaryRow` (read model) | you | table mapping only, no behaviour |

Every crossing is a MapStruct mapper on the shared configuration — `…ApiMapper` in `..api..`,
`…PersistenceMapper` in `..persistence..` (`be-mapstruct-dto-layers`). A property that is not
mapped is a compile error.

**Writes always go through the domain; reads may skip it** (ADR-STACK-00005 point 6):

- write: `CreateOrderDto` → `…ApiMapper` → command → service loads the domain record from the write
  repository, applies the rule, saves → result domain record → `…ApiMapper` → `OrderDto`;
- read: API delegate → query service (`@Transactional(readOnly = true)`) → read repository returns
  `…Row` / page of `…Row` → `…ApiMapper` → `…Dto`. Even a read restricts rows to what the user may
  see (ADR-STACK-00005 leaves the mechanism open; it is decided separately — do not invent one).

## Layer rules

**API delegate** (`..api..`)

```java
@Component
@RequiredArgsConstructor
public class OrdersApiDelegateImpl implements OrdersApiDelegate {   // generated delegate interface

    private final OrderService orderService;
    private final OrderApiMapper mapper;

    @Override
    @PreAuthorize(Roles.HAS_USER)                                    // every operation: a concrete role
    public ResponseEntity<OrderDto> createOrder(CreateOrderDto body) {
        Order created = orderService.create(mapper.toCommand(body));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toDto(created));
    }
}
```

- Implements the generated `…ApiDelegate` (delegate pattern; generator options are owned by
  `be-api-contract-first`). The generated `…ApiController` carries the mapping annotations and
  bean validation and calls the delegate; never write a `@RestController` of your own.
- Every operation is overridden; one that is not answers `501` (a web test per operation catches it).
- `@PreAuthorize` with a role constant on every public method (`be-oauth2-resource-server`).
- Maps, makes **one** service call, maps back. No business decision, no `@Transactional`, no
  repository, no `try/catch` for flow control — exceptions go to the one global handler
  (`be-problem-details-rfc9457`).
- A streamed export passes a mapping function into the query service, so mapping runs row by row
  inside the transaction (see `be-jdbc-client-reads`).

**Service** (`..service..`)

```java
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orders;               // write repository (domain in, domain out)
    private final CustomerService customers;           // other module: through its service
    private final Clock clock;

    @Transactional
    public Order create(CreateOrderCommand command) {
        customers.requireActive(command.customerId());
        return orders.save(Order.create(command, clock));
    }
}
```

- A concrete class with constructor injection; an interface only with a concrete reason
  (ADR-STACK-00001 point 7: generated implementation, module or external-system boundary, several
  implementations).
- **One service operation = one transaction** (ADR-STACK-00005 point 14). It may change several
  aggregates. Isolation is PostgreSQL's default `READ COMMITTED` — do not set another level.
- A version conflict leaves the service as `OptimisticLockingFailureException` and becomes `409`
  **without retry**; a deadlock is retried around the whole transaction, after a full rollback, from
  a separate bean, and only for operations explicitly marked as idempotent (ADR-STACK-00005 point
  14); retrying **once** is this skill's choice, not the ADR's. Mechanics in `be-transactional-patterns`; HTTP mapping in
  `be-problem-details-rfc9457`.
- Takes and returns domain types, commands or `…Row` read models — never `…Dto`, `…Entity`,
  `HttpServletRequest` or `ResponseEntity`.
- Cross-module calls go service → service. Never into another module's persistence package.
- Time from an injected `Clock`; "today" in the configured application time zone (IANA id, example: Europe/Prague; ADR-STACK-00005 point 15, ADR-STACK-00008 point 7).

**Write repository** (`..persistence..`) — a `@Repository` class that wraps a package-private
Spring Data JDBC `ListCrudRepository<…Entity, Long>` and the `…PersistenceMapper`; exposes only
domain records; returns the saved copy (it carries the new `id` / `version`). Aggregate rules,
`@Version` and child collections: `be-spring-data-jdbc-aggregates`.

**Read repository** (`..persistence..`) — a `@Repository` class with `JdbcClient`; named
parameters, fixed SQL fragments, `…Row` results, pages of at most 500 rows, streams only for
export and batches (ADR-STACK-00005 point 7): `be-jdbc-client-reads`.

Technical tables of `backend-service` (job queue, audit) use SQL via
`JdbcClient` without domain model or aggregates; a bulk write from domain objects may use batched
SQL (ADR-STACK-00005 point 4).

**Database** — schema only from Flyway migrations; declarative constraints; no procedures or
triggers with business logic (`db-flyway-migrations`, `db-design-postgres-tables`).

## Checklist: adding a field

In this order, in one pull request (example: an optional `note`, at most 500 characters, on an
existing record type):

1. **Description** in `docs/design/backend/<area>/` (or the PR text while that folder is missing):
   field, type, optional/required, limit, who may write it.
2. **Model** (`api-model`): the response record; for a writable field also the create/update
   request records — `be-api-contract-first` and its `fields.md` (`@Size(max = 500)`,
   required/nullable). `./mvnw -B verify` re-exports `specs/`; commit it.
3. **Migration**: `V<next>__add_<table>_<column>.sql` with the same limit
   (`db-flyway-migrations`, `db-design-postgres-tables`); a nullable column without a volatile
   default needs no table rewrite.
4. **Read path** (field only shown): `…Row` record + the column in the read repository's SQL
   (`be-jdbc-client-reads`) → `…ApiMapper` maps it (an unmapped property fails the compile).
5. **Write path** (field also stored): the command record and the domain record get the field, the
   service passes it into the aggregate, `…Entity` gets the column, `…PersistenceMapper` maps both
   ways (`be-spring-data-jdbc-aggregates`, `be-mapstruct-dto-layers`); the delegate stays a pure map +
   one service call.
6. **Tests**: mapper round trip (`*Test`), a web test of each touched operation incl. the `422` for
   an overlong value, an `*IT` that stores and reads the value (`be-testing-conventions`).
7. **Guards**: `backend/scripts/api-guards.sh` — a new optional field needs no `api-breaking` label.

## Dependency direction (ArchUnit)

```
api ──► service ──► domain ◄── persistence
 │         └──────► persistence (write + read repositories)
 └──► persistence: only …Row read models
```

- `..domain..` depends on nothing of Spring Data, Jackson, generated API types or persistence.
- `..api.generated..` types are used only in `..api..`; `…Entity` never reaches `..api..`.
- Spring Data repositories are called only from the write path of services (ADR-STACK-00005: writes
  through Spring Data JDBC aggregates, reads through `JdbcClient`).
- `@Transactional` only in `..service..`; `@Mapper` only with the shared config; no field
  injection; no `System.out`. Rule code: `be-archunit-rules`.

## Cross-cutting

- Lombok only for `@Slf4j`, `@RequiredArgsConstructor`, `@Builder` in hand-written code; data
  carriers are records, including `@ConfigurationProperties` (`be-lombok`).
- JSON is Jackson 3 (`tools.jackson`): customise with `JsonMapperBuilderCustomizer` / `JsonMapper`
  beans, never a hand-made `ObjectMapper`.
- Numbers: IDs `Long`, exact values `BigDecimal`, never `double` for money or effort
  (`be-numbers-and-ids`).
- Configuration values that differ between environments come from validated
  `@ConfigurationProperties` records — no constants, no scattered `@Value`.
- Logging and the correlation ID header: `be-correlation-logging`. Errors: one global handler,
  `be-problem-details-rfc9457`. Scheduled jobs: `be-scheduling-shedlock`.
- Tests: `*Test` (unit, slice; Surefire) and `*IT` (Spring context + Testcontainers; Failsafe) —
  `be-testing-conventions`.

## Gotchas
- Agent puts business logic in the API delegate, a mapper or a repository — domain or service
- Agent injects a repository into the API delegate — one service call per endpoint
- Agent writes a `@RestController` next to the generated controller — implement the generated `…ApiDelegate`
- Agent passes `…Dto` into a service or returns `…Entity` from it — services speak domain types
- Agent writes static `OrderDto.from(order)` or manual mapping loops — MapStruct mapper
- Agent puts `@Transactional` on the API delegate or a repository class — services only
- Agent sets `isolation = SERIALIZABLE` or retries version conflicts — `READ COMMITTED`; conflict → `409`
- Agent adds `interface OrderService` + `OrderServiceImpl` without a reason — concrete class
- Agent creates a Spring profile `scheduler` / `api` that enables classes — role by module only
- Agent adds `AutoConfiguration.imports` to a `lib-*` module — one entry `@Configuration`, imported explicitly
- Agent puts the main class in package `app` — it scans `app.lib..`; libraries become active without `@Import`
- Agent calls another module's repository — call that module's service
- Agent adds JPA (`@Entity`, `JpaRepository`) — Spring Data JDBC + `JdbcClient` only
- Agent edits or commits generated API code — change the model, regenerate

## Provenance

Rewritten for this application's Java base (JDK 25, Spring Boot 4, contract-first API, Spring Data
JDBC + `JdbcClient`, MapStruct, three object layers). Layer responsibilities and service rules
derive from `skills/spring-boot-4/layered-architecture` in
[rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills) (MIT,
© Rrezart Prebreza).
