---
name: be-domain-driven-design
description: >
  Use when designing or evolving aggregates, invariants, value objects or domain events in a
  Spring Boot 4 application with a layered architecture and Spring Data JDBC persistence.
  Covers the split between the domain model and the persisted aggregate, aggregate
  boundaries, value objects, commit-bound events and anti-corruption translation.
---

# Domain-Driven Design

The Elysium Java base is a **layered** application (`@RestController` → `@Service` →
`@Repository`), not hexagonal: there are no ports and no adapter interfaces for their own sake.
Business decisions live in the domain model and in `@Service` classes; persistence is Spring
Data JDBC for writes and `JdbcClient` for reads. There is no JPA.

## Domain Model vs. Persisted Aggregate

Three type families, each in its own layer:

| Layer | Type | Example | Knows about |
|---|---|---|---|
| API | generated `…Dto` | `OrderDto` | the contract only |
| Domain | plain class or record, no suffix | `Order`, `Money` | business rules; no Spring Data, no Jackson annotations |
| Persistence | `…Entity` (aggregate root) / `…Row` (read projection) | `OrderEntity`, `OrderSummaryRow` | table mapping (`@Table`, `@Id`, `@Version`, `@MappedCollection`) |

- The domain object enforces invariants; the `…Entity` is a dumb mapping of the aggregate to
  tables. Mapping between them is a MapStruct `…PersistenceMapper` in the persistence layer.
- **Writes always go through the domain**: load → domain behavior → save. **Reads may bypass it**:
  a query returns `…Row` projections via `JdbcClient` and is mapped straight to the API.
- One Spring Data repository per aggregate root, working on the `…Entity`; the `@Repository`
  class around it exposes domain types to the service.

```java
// Persistence (Spring Data JDBC aggregate, no behavior)
@Table("customer_order")
public record OrderEntity(
        @Id Long id,
        @Version Long version,
        Long customerId,                                // reference to another aggregate by ID
        short orderStatusId,                            // FK to the order_status lookup table
        BigDecimal totalAmount,
        @MappedCollection(idColumn = "order_id", keyColumn = "line_no") List<OrderItemEntity> items) {
}

interface OrderJdbcRepository extends ListCrudRepository<OrderEntity, Long> { }

@Repository
public class OrderRepository {
    private final OrderJdbcRepository jdbc;
    private final OrderPersistenceMapper mapper;

    public OrderRepository(OrderJdbcRepository jdbc, OrderPersistenceMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<Order> findById(Long id) { return jdbc.findById(id).map(mapper::toDomain); }

    public Order save(Order order) { return mapper.toDomain(jdbc.save(mapper.toEntity(order))); }
}
```

## Aggregate Rules
- One repository per aggregate root; child entities have no repository of their own
- External code only accesses the aggregate through the root — never child entities directly
- Reference other aggregates **by ID** (`Long customerId`), never by object; Spring Data JDBC does
  not load across aggregates and has no lazy loading
- The whole aggregate (root + `@MappedCollection` children) is loaded and saved as one unit,
  versioned by the root's `@Version`; keep aggregates small enough for that to be cheap
- Choose boundaries from invariants that must commit atomically and expected contention,
  not a fixed number of child entities. Splitting requires an explicit consistency plan.

The snippets are illustrative and require the application's domain types and imports.
Keep existing public identifiers and persistence mappings unless the task includes a migration.

```java
// GOOD: Aggregate root controls all access to children
order.addItem(productId, quantity); // through root
order.removeItem(lineNo);           // through root

// BAD: Direct child access from outside
order.getItems().add(new OrderItem(...)); // bypasses invariants
```

## Identifiers

- IDs are database identities (`bigint GENERATED ALWAYS AS IDENTITY`, upper bound 2^53−1),
  `Long` in Java. A new aggregate has `id == null` until the first `save(...)`; never generate
  IDs in Java (no `UUID.randomUUID()` for primary keys).
- Typed ID wrappers (`record OrderId(Long value)`) are optional inside the domain. If the
  persisted `…Entity` uses them, register Spring Data JDBC read/write converters; the API keeps
  plain `int64` numbers.

## Value Objects

Immutable, no identity, equality by value:

```java
public record Money(BigDecimal amount, Currency currency) {
    public Money {
        Objects.requireNonNull(amount);
        Objects.requireNonNull(currency);
        if (amount.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("Amount cannot be negative");
    }

    public Money add(Money other) {
        if (!currency.equals(other.currency))
            throw new CurrencyMismatchException(currency, other.currency);
        return new Money(amount.add(other.amount), currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currency));
    }
}

public record EmailAddress(String value) {
    public EmailAddress {
        if (!value.matches("^[\\w.-]+@[\\w.-]+\\.[a-z]{2,}$"))
            throw new InvalidEmailException(value);
    }
}
```

See `templates/Money.java` for scale handling (the scale follows the currency and rounding is refused).
Money and other exact quantities are `BigDecimal` (database `numeric(p, s)`), never `double` or
`float`. A value object is persisted as columns of its owner (`@Embedded` in the `…Entity`, or
flattened by the persistence mapper), not as its own table.

## Domain Events

```java
// Event — immutable record
public record OrderPlaced(Long orderId, Long customerId, Money total, Instant occurredAt) {
    public static OrderPlaced of(Order order) {
        return new OrderPlaced(order.getId(), order.getCustomerId(), order.getTotal(), Instant.now());
    }
}

// Collect events in the domain object
public class Order {
    private final List<Object> domainEvents = new ArrayList<>();

    public void place() {
        this.status = OrderStatus.PLACED;
        domainEvents.add(OrderPlaced.of(this));
    }

    public List<Object> pullDomainEvents() {
        var events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }
}

// Publish inside the transaction; save() is not commit.
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(OrderRepository orderRepository, ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Order placeOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.place();
        List<Object> events = order.pullDomainEvents();     // the saved copy is a new object
        Order saved = orderRepository.save(order);
        events.forEach(eventPublisher::publishEvent);       // commit-bound listener below
        return saved;
    }
}

// Listen to events — bind to commit, not just publish.
// @EventListener fires synchronously inside the TX; if the TX later rolls back you've
// already sent the email. AFTER_COMMIT avoids rollback delivery but is best effort.
@Component
public class OrderPlacedHandler {
    private final EmailService emailService;

    public OrderPlacedHandler(EmailService emailService) {
        this.emailService = emailService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderPlaced(OrderPlaced event) {
        emailService.sendOrderConfirmation(event.customerId(), event.orderId());
    }
}
```

> Spring Data's `@DomainEvents` / `@AfterDomainEventPublication` (or `AbstractAggregateRoot`) only
> fire for the type the repository saves. With a separate domain model the events live on the
> domain object, so the service publishes them explicitly as above.

Neither publication mechanism guarantees durable delivery. For required effects, write a row
into a job/outbox table in PostgreSQL in the same transaction as the aggregate change; a worker
picks it up with `FOR UPDATE SKIP LOCKED`, with idempotent processing and retry/recovery. The
base has no message broker. `@Async` changes execution, not durability. Post-commit database
writes need a new transaction because the original resources may still be bound after
completion. See [transaction boundaries](../be-transactional-patterns/SKILL.md) and
[idempotency](../be-idempotency-patterns/SKILL.md).

## Queries

Complex searches are read-side queries, not aggregate behavior: a `JdbcClient` query with named
parameters returns `…Row` projections (or a `Stream` of them, consumed inside the transaction).
Filters are composed from validated parameters into fixed SQL fragments — never by
concatenating user input. There are no JPA `Specification`s.

## Anti-Corruption Layer (ACL)
- When integrating with external systems or legacy code, don't let their models leak into your domain
- Create an ACL — a translation class that converts external data to your domain language
- The ACL lives in the integration/infrastructure part of the code, not in the domain; the
  service calls it directly. Add an interface only when there is a real second implementation
  or a test seam you cannot get otherwise.

```java
// GOOD — ACL translates the external payment API to domain concepts
@Component
public class PaymentGatewayClient {

    private final ExternalPaymentClient client;  // third-party SDK or @HttpExchange client

    public PaymentGatewayClient(ExternalPaymentClient client) {
        this.client = client;
    }

    public PaymentConfirmation charge(Long orderId, Money amount) {
        // Translate domain → external
        PaymentApiRequest apiRequest = new PaymentApiRequest(
            orderId.toString(),
            amount.amount(), // illustrative provider accepts BigDecimal
            amount.currency().getCurrencyCode());

        // Call external system
        PaymentApiResponse apiResponse = client.charge(apiRequest);

        // Translate external → domain
        return new PaymentConfirmation(
            apiResponse.getTransactionId(),
            apiResponse.isSuccessful() ? PaymentStatus.CONFIRMED : PaymentStatus.DECLINED);
    }
}
```

Use the provider's exact monetary representation. For integer minor units, convert with
the currency's fraction digits and exact arithmetic; do not silently round or convert
money to binary floating point.

## Verification

Test aggregate invariants on the domain object without Spring; test the persistence mapping
(domain ↔ `…Entity`) round trip against PostgreSQL; test rollback preventing external
notification, and recovery from a crash after commit for durable publication. Verify that
existing API IDs and database relationships remain compatible with the requested change.

## Gotchas
- Agent puts `@Table`/`@Id`/`@Version` or Jackson annotations on the domain class — mapping annotations belong on the `…Entity`, the API shape on the generated `…Dto`
- Agent uses JPA (`@Entity`, `@OneToMany`, `Specification`, lazy loading) — the base has no JPA
- Agent references another aggregate by object — reference by ID; Spring Data JDBC never loads across aggregates
- Agent saves a child entity through its own repository — children are saved with their root
- Agent generates primary keys in Java (`UUID.randomUUID()`) — IDs are database identities (`Long`)
- Agent introduces ports and adapter interfaces — the architecture is layered; an interface needs a concrete reason
- Agent creates anemic models with only getters/setters — put behavior on domain objects
- Agent replaces existing `Long` API IDs with value objects - preserve the public contract; map internal types explicitly.
- Agent puts domain logic in controllers or repositories — decisions live in the domain model and `@Service`
- Agent accesses child entities directly from outside — always go through aggregate root
- Agent confuses save with commit - publish in the transaction and select listener/durable delivery semantics deliberately.
- Agent splits an aggregate at an arbitrary child count - derive boundaries from business invariants.
- Agent lets external API models into domain — use an Anti-Corruption Layer to translate
- Agent adds nullable domain APIs without documenting them - follow the project's JSpecify conventions and mark nullable returns.
- Agent uses removed Boot 4 test annotations - use `@MockitoBean` in place of `@MockBean`.

## Official sources

- [Spring Data JDBC — aggregates and entity persistence](https://docs.spring.io/spring-data/relational/reference/jdbc/entity-persistence.html)
- [Spring Data aggregate event publication](https://docs.spring.io/spring-data/relational/reference/repositories/core-domain-events.html)
- [Spring transaction-bound events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)

## Provenance

Adapted for the Elysium Java base (JDK 25, Spring Boot 4, layered architecture, Spring Data JDBC + `JdbcClient`, PostgreSQL 18).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/domain-driven-design`, MIT License, © Rrezart Prebreza.
Changes: domain model separated from the persisted Spring Data JDBC aggregate, no JPA
(Specifications removed), database identities as `Long` IDs, ACL without ports, outbox in
PostgreSQL instead of a broker.
