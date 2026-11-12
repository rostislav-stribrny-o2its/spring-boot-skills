---
name: be-backend-layers
description: >
  Use when creating or changing any class of a Spring Boot 4 backend — API delegates, services,
  domain classes, repositories, mappers, configuration. Defines the layers (generated
  controller → API delegate → @Service → @Repository), the three type families (…Dto, domain,
  …Entity/…Row) with their MapStruct mappers, the read and write paths, packages and
  cross-cutting rules, so that business logic stays in one place.
---

# Backend Layers

The Elysium Java base is a **layered** application. Each layer has one job; ArchUnit tests
enforce the direction (see `be-archunit-rules`).

```
generated controller   ← from the contract (api-contract-first); never edited
      ↓ …Dto
API delegate           ← @Component …ApiDelegateImpl: …ApiMapper (MapStruct), ONE service call
      ↓ domain types / commands                     ↑ domain types or …Row projections
@Service               ← ALL business logic, @Transactional, orchestration
      ↓ domain types
@Repository            ← persistence: Spring Data JDBC aggregates + …PersistenceMapper (writes),
      ↓                  JdbcClient queries returning …Row / Stream<…Row> (reads)
PostgreSQL             ← structure and declarative constraints only, no business logic
```

## Three type families

| Family | Naming | Where | Rules |
|---|---|---|---|
| API | `OrderDto`, `CreateOrderDto` | generated from the spec | used only in the API layer |
| Domain | `Order`, `Money`, `CreateOrderCommand` | `…domain` / `…service` | behavior and invariants; no Spring Data, no Jackson, no validation-for-HTTP annotations |
| Persistence | `OrderEntity` (aggregate root written by Spring Data JDBC), `OrderSummaryRow` (read projection) | `…persistence` | table mapping only, no behavior; never leaves the persistence and read paths |

Mappers are MapStruct interfaces that all use one shared configuration
`@MapperConfig(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR,
typeConversionPolicy = ReportingPolicy.ERROR)` — a forgotten field or a lossy conversion is a
compile error (ArchUnit checks that every mapper uses it):
- `…ApiMapper` — `…Dto` ↔ domain (and `…Row` → `…Dto` on the read path), in the API layer;
- `…PersistenceMapper` — domain ↔ `…Entity`, in the persistence layer.
No static `from(...)` factories on DTOs, no hand-written mapping loops (see `be-mapstruct-dto-layers`).

## Write path and read path

- **Writes always go through the domain:** delegate maps `…Dto` → command/domain → service
  loads the aggregate via the repository (domain object), calls its behavior, saves → delegate
  maps the result to `…Dto`.
- **Reads may bypass the domain:** a query service (`@Transactional(readOnly = true)`) calls a
  read repository (`JdbcClient`) that returns `…Row` projections — a query service may return
  `…Row`s; the delegate maps `…Row` → `…Dto`.
- **Large reads stream:** the repository returns a `Stream<…Row>`; it is consumed in
  try-with-resources **inside the query service's transaction**. The delegate passes the mapping
  as a function, so mapping happens row by row inside the transaction, rows become
  garbage-collectable immediately, and only the finished result leaves:

```java
// query service
@Transactional(readOnly = true)
public <R> R exportOrders(LocalDate day, Function<Stream<OrderRow>, R> consumer) {
    try (Stream<OrderRow> rows = orderReadRepository.streamByDay(day)) {
        return consumer.apply(rows);
    }
}

// delegate
List<OrderLineDto> lines = orderQueries.exportOrders(day, rows -> rows.map(mapper::toDto).toList());
```

## Layer rules

**Generated controller + API delegate**
- The controller is generated; the delegate (`@Component`, not `@Service`) maps and calls exactly
  one service method. No business decisions, no `@Transactional`, no repository injection.
- Validation of the request shape comes from the contract (`useBeanValidation`); business
  validation belongs to the domain/service.
- Exceptions propagate to one `@RestControllerAdvice` producing Problem Details; no try/catch
  for flow control.

**`@Service`**
- All business logic, rules and orchestration; `@Transactional` of your own code lives here —
  never on your delegates or your `@Repository` classes. The built-in transactional behavior of
  Spring Data repositories stays untouched (see `be-transactional-patterns`).
- Takes and returns domain types or commands — never `…Dto`, `…Entity`, `HttpServletRequest` or
  `ResponseEntity`.
- One service per aggregate or use-case area, not god classes; a separate query service for the
  read side is fine.
- Retries and concurrency limits with Framework 7 `@Retryable` / `@ConcurrencyLimit`
  (`@EnableResilientMethods`), see `be-resilience-retry`.

**`@Repository`**
- Write side: wraps a Spring Data JDBC repository of the aggregate root (`ListCrudRepository<OrderEntity, Long>`)
  and maps with the `…PersistenceMapper`; exposes domain types to the service.
- Read side: `JdbcClient` with named parameters, returning `…Row` records, `Optional<…Row>` or
  `Stream<…Row>`; SQL composed only from fixed fragments and bound parameters.
- No business logic, no JPA, no `Object[]` results.

**Database**
- Schema only through Flyway; constraints are declarative; no triggers or procedures for business
  logic (see `db-flyway-migrations`, `db-design-postgres-tables`).

## Packages

Package by **design module** first, then by layer:

```
com.example.<app>.<module>.api           delegate implementations, …ApiMapper
com.example.<app>.<module>.api.generated generated controllers and …Dto (target/, not committed)
com.example.<app>.<module>.service       @Service, commands
com.example.<app>.<module>.domain        domain classes, value objects, domain events
com.example.<app>.<module>.persistence   @Repository, …Entity, …Row, …PersistenceMapper, Spring Data repositories
com.example.<app>.config                 @Configuration, @ConfigurationProperties
```

Cross-module calls go service → service (never into another module's persistence).

## Configuration

- Every value that differs between environments (URLs, credentials, limits, schedules, feature
  switches) comes from configuration bound to a validated `@ConfigurationProperties` record —
  never a constant in code, never `@Value` scattered over classes (see `be-configuration-properties`).
- `@Configuration` classes wire infrastructure only and never depend on services.

## Cross-cutting rules

- Constructor injection only. Lombok is optional in the base: where the project enables it,
  `@RequiredArgsConstructor` and `@Slf4j` are fine; data carriers are `record`s either way.
- Logging via SLF4J, never `System.out`; no secrets or personal data in logs.
- JSON is Jackson 3 (`tools.jackson`): customize with `JsonMapperBuilderCustomizer` / `JsonMapper`
  beans and `@JacksonComponent`, not `ObjectMapper` / `@JsonComponent`.
- An interface is introduced only with a concrete reason (a second implementation, a module
  boundary); no ports and adapters for their own sake.
- Numbers: IDs `Long`, exact values `BigDecimal`; never `double`/`float` for money or quantities;
  no `intValue()`/`longValue()` on `BigDecimal`/`BigInteger` outside a dedicated converter.

## Opereta

- Code and identifiers in English, **comments in code in Czech**.
- Lombok is enabled (`@RequiredArgsConstructor`, `@Slf4j`); data carriers stay `record`s.
- Business logic of the earlier proof of concept written in PL/pgSQL moves into `@Service`
  classes; nothing of it stays in the database.
- Modules `backend` and `integration-service` follow the same layers; `integration-contract`
  holds only shared `…Row` contract types and enums.

## Gotchas
- Agent puts business logic in the delegate, a mapper or a repository — it belongs in the domain or `@Service`
- Agent injects a repository into the delegate — the delegate calls one service method
- Agent passes `…Dto` into a service or returns `…Entity` from it — services speak domain types
- Agent writes static `OrderDto.from(order)` factories or manual mapping loops — MapStruct mappers
- Agent puts `@Transactional` on the delegate or its own `@Repository` class — on services only (Spring Data's built-in repository transactions stay)
- Agent annotates the delegate with `@Service` — it is an adapter (`@Component`)
- Agent uses JPA (`JpaRepository`, `@Entity`, `@EnableJpaAuditing`) — Spring Data JDBC + `JdbcClient` only
- Agent loads entities item by item to build a list screen — a read query returning projections
- Agent returns an open `Stream` to the delegate — the query service consumes it inside its transaction, applying the delegate's mapping function
- Agent creates a `@Configuration` that depends on a `@Service` — configuration wires infrastructure only
- Agent hard-codes environment values — `@ConfigurationProperties`
- Agent uses `@Autowired` field injection — constructor injection
- Agent defines an `ObjectMapper` bean — Boot 4 uses Jackson 3 `JsonMapper`
- Agent adds spring-retry — Framework 7 has `@Retryable` built in

## Provenance

Rewritten for the Elysium Java base (JDK 25, Spring Boot 4, contract-first API, Spring Data JDBC + `JdbcClient`,
MapStruct, three type families). Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/layered-architecture` (layer responsibilities, service and configuration rules), MIT License,
© Rrezart Prebreza. The original is kept in `_unused/skills/spring-boot-4/layered-architecture`.
Opereta layer: section "Opereta".
