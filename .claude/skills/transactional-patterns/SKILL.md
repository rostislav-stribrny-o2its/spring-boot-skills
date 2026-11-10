---
name: transactional-patterns
description: >
  Use when defining or debugging Spring Boot 4 database transaction boundaries, rollback,
  propagation, optimistic-lock retries with Spring Data JDBC, streaming reads inside a
  transaction, or commit-bound events. For duplicate HTTP commands use idempotency-patterns.
---

# Transactional Patterns

## Establish the boundary

- Inspect the project's Boot/Framework version, transaction manager, rollback configuration
  and caller before changing propagation. Persistence in the Elysium Java base is Spring Data
  JDBC (aggregate writes) plus `JdbcClient` (reads) on one `DataSourceTransactionManager`;
  there is no JPA persistence context, no lazy loading and no dirty checking — a change is
  written only when `save(...)` is called.
- Place multi-step business transactions at the application/service boundary. Preserve
  Spring Data repository transactions; repository annotations are not inherently wrong.
- Default propagation is `REQUIRED` — joins existing transaction or creates one
- Use `readOnly = true` for appropriate read units of work. It is an optimization hint,
  not write prevention, authorization or automatic routing to a read replica.
- Keep existing architecture and transaction-manager choices. A local database transaction
  does not make writes to another database or an HTTP provider atomic.

The snippets below are illustrative; domain classes and imports come from the application.
Adapt the [service template](templates/TransactionalOrderService.java) and compare the
[good](examples/good-transactional-service.java) and [bad](examples/bad-transactional-service.java)
examples when editing a service.

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true) // default for all methods in this service
public class OrderService {

    @Transactional // overrides readOnly for writes
    public Order createOrder(CreateOrderCommand command) { // domain input, mapped from the generated …Dto
        inventoryService.reserve(command.items()); // participates in same TX
        return orderRepository.save(Order.from(command));
    }

    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id); // readOnly = true inherited
    }
}
```

## Propagation

| Propagation | Behavior |
|-------------|----------|
| `REQUIRED` (default) | Join existing TX or create new |
| `REQUIRES_NEW` | Always create new TX, suspend existing |
| `SUPPORTS` | Join if exists, proceed without TX if not |
| `NOT_SUPPORTED` | Always run without TX |
| `MANDATORY` | Must have existing TX, throw if not |
| `NEVER` | Must NOT have TX, throw if one exists |

Participating `REQUIRED` calls use the outer transaction's isolation, timeout and read-only
settings. Catching an inner failure does not clear its rollback-only marker; outer commit
may still throw `UnexpectedRollbackException`.

`REQUIRES_NEW` needs another connection while the outer transaction retains its resources.
Account for pool capacity and lock contention. Reserve independent commits for records
that must survive a failed operation (for example, an attempted action), not a success
audit row that claims a rolled-back order was created. Do not reference an uncommitted
parent row from an independent audit transaction.

```java
// REQUIRES_NEW — for audit logging that must survive rollback
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void logAuditEvent(AuditEvent event) {
    auditRepository.save(event); // commits independently of parent TX
}

// Illustrative: record an attempt for an existing order; failures are unchecked.
@Transactional
public void processOrder(Order order) {
    auditService.logAuditEvent(new AuditEvent("ORDER_START", order.getId()));
    try {
        // ... process, may throw
    } catch (RuntimeException e) {
        auditService.logAuditEvent(new AuditEvent("ORDER_FAILED", order.getId()));
        throw e; // parent TX rolls back, audit TX already committed
    }
}
```

## Self-Invocation Pitfall

```java
// ❌ BROKEN — self-invocation bypasses Spring proxy, @Transactional ignored
@Service
public class OrderService {
    @Transactional
    public void processAll(List<Long> ids) {
        ids.forEach(id -> this.processSingle(id)); // bypasses proxy!
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processSingle(Long id) { ... } // never creates new TX
}

// ✅ FIX — extract the independently transactional operation to another bean
@Service
@RequiredArgsConstructor
public class OrderService {
    private final OrderProcessor orderProcessor; // separate bean

    @Transactional
    public void processAll(List<Long> ids) {
        ids.forEach(id -> orderProcessor.processSingle(id)); // goes through proxy
    }
}
```

## Handling Exceptions

```java
// Default: RuntimeException and Error roll back; checked exceptions do not.
// Check project-wide rollback configuration before adding per-method rules.

@Transactional(rollbackFor = InsufficientInventoryException.class) // checked exception
public Order createOrder(CreateOrderCommand command) throws InsufficientInventoryException {
    ...
}

```

Never use `noRollbackFor` or a `catch` inside the transaction to recover from an optimistic-lock
conflict (`OptimisticLockingFailureException`): the data read in this transaction is stale. Roll it
back and retry the whole unit of work in a fresh transaction, with a bounded policy and a fresh
aggregate read.

## Optimistic Locking

Spring Data JDBC checks the `@Version` column of the aggregate root on every `save(...)`:
the `UPDATE … WHERE id = ? AND version = ?` affects no row when another transaction changed the
aggregate in between, and the repository throws `OptimisticLockingFailureException`.

```java
@Table("customer_order")
public class OrderEntity {           // persisted aggregate root (Spring Data JDBC, no JPA annotations)
    @Id private Long id;
    @Version private Long version;   // matches the version column of the table
    // ...
}

// Handles concurrent updates
@Transactional
public Order updateStatus(Long id, OrderStatus newStatus) {
    Order order = orderRepository.findById(id).orElseThrow(); // domain object, carries the version
    order.updateStatus(newStatus);
    return orderRepository.save(order); // mapped to OrderEntity; conflict surfaces HERE, at save
}
```

The domain object carries the version it was read with; the persistence mapper copies it to the
entity, so the check compares against what this unit of work actually read.

The whole aggregate (root + `@MappedCollection` children) is written and versioned together;
changes to a child entity must go through the root and its `save(...)`.

## Retrying Transient Failures

Retry support ships in core Spring Framework (`org.springframework.resilience.annotation`) — no
Spring Retry dependency. Enable once with `@EnableResilientMethods`, then retry transient failures
such as optimistic-lock conflicts:

```java
@Configuration
@EnableResilientMethods
public class ResilienceConfig {}

@Service
@RequiredArgsConstructor
public class OrderStatusFacade {

    private final OrderService orderService; // separate bean — retry must wrap the TX

    @Retryable(includes = OptimisticLockingFailureException.class,
               maxRetries = 3, delay = 50, jitter = 25)
    public Order updateStatus(Long id, OrderStatus newStatus) {
        return orderService.updateStatus(id, newStatus); // fresh @Transactional per attempt
    }
}
```

Put `@Retryable` on a method that *calls* the `@Transactional` method on another bean — each attempt
needs a fresh transaction. Retrying inside the failed transaction re-runs code in a TX already marked
rollback-only. For hot write paths, `@ConcurrencyLimit(10)` (same package) caps concurrent invocations
instead of letting contention turn into retry storms.

Use the [version-specific retry guidance](../resilience-retry/SKILL.md) for dependencies
and annotation imports. A retrying facade must not already hold a transaction that each
attempt's `REQUIRED` method would join.

## Streaming Reads Need a Transaction

PostgreSQL's JDBC driver streams a result set (instead of loading every row into memory) only
when autocommit is off — i.e. inside a transaction — **and** a fetch size is set. A query that
returns a `Stream` must therefore be consumed inside a `@Transactional` method, in
try-with-resources, and the result must be complete before the method returns:

```java
@Transactional(readOnly = true)
public ExportSummary exportOrders(LocalDate day, OrderCsvWriter writer) {
    try (Stream<OrderRow> rows = orderReadRepository.streamByDay(day)) { // fetch size set
        rows.map(OrderCsvLine::from).forEach(writer::write);                // row by row
    }
    return writer.summary(); // only the finished result leaves the transaction
}
```

- Set the fetch size once: `spring.jdbc.template.fetch-size` (used by the auto-configured
  `JdbcTemplate` behind `JdbcClient`), or on the query.
- The stream must never outlive the transaction that opened it: whoever consumes it (the
  service, or the API delegate it is passed to) runs inside that transaction, and the
  connection is released only after the try-with-resources block closes the stream.
- Query details (`JdbcClient`, keyset pagination, projections) belong to the `jdbc-client-reads` skill.

## Distributed Transactions (Saga Pattern)

A saga coordinates durable local steps; wrapping remote calls in `@Transactional`
does not implement one.

1. Commit the pending order, saga state and outgoing command in one local transaction.
2. Dispatch the command outside that transaction, using a stable provider idempotency key.
3. Persist each confirmed outcome and the next command atomically in another local transaction.
4. On rejection, persist failure state and a compensation command. Retry compensation
   independently, and reconcile timeouts where the remote outcome is unknown.

Do not save failure state and then throw a rollback-triggering exception from the same
transaction: that erases the state. Recovery must resume from durable state after a crash.
Use the project's existing workflow mechanism. The Elysium base has no message broker: durable
work and outgoing commands go through a job/outbox table in PostgreSQL, picked up with
`FOR UPDATE SKIP LOCKED`; see [idempotency](../idempotency-patterns/SKILL.md) for duplicate effects.

## Side Effects After Commit

For best-effort local notifications, a commit-bound listener prevents delivery on rollback.
For required delivery, persist an outbox entry with the business change or use the project's
durable publication mechanism. `AFTER_COMMIT` alone cannot recover a process crash or retry
a failed delivery.

```java
// Publisher — inside the TX
@Transactional
public Order place(Long id) {
    Order order = orderRepository.findById(id).orElseThrow();
    order.place();
    eventPublisher.publishEvent(new OrderPlaced(order.getId())); // published now; listener defers handling
    return orderRepository.save(order);
}

// Listener — runs ONLY if the TX commits successfully
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderPlaced(OrderPlaced event) {
    emailService.sendConfirmation(event.orderId()); // best effort; no durable retry
}
```

`AFTER_COMMIT` runs after the DB commits, but resources may still be bound to the completed
transaction. Delegate database writes to a separate `REQUIRES_NEW` bean. Listener failure
cannot undo the committed business write; make delivery failures observable and recoverable.
Without an active transaction the listener is skipped by default. `@Async` does not add
durable delivery. See [domain events](../domain-driven-design/SKILL.md).

## Verification

Test through Spring proxies and verify committed data from a new transaction. Cover checked
versus unchecked failures, inner rollback-only propagation, optimistic conflicts at commit,
audit rollback, and listener behavior after commit and rollback. Use the production database
engine for isolation and contention tests. A test-wide rollback can hide after-commit behavior.

## Gotchas
- Agent removes repository transaction annotations - keep the unit of work and existing Spring Data behavior.
- Agent treats AFTER_COMMIT as durable messaging - use an outbox or persistent publication registry for required delivery.
- Agent uses readOnly for replica routing or write protection - neither follows from the flag alone.
- Agent commits success audit entries with REQUIRES_NEW - commit them with the business mutation.
- Agent suppresses optimistic-lock rollback - retry in a fresh transaction.
- Agent uses JPA idioms (`@Entity`, `EntityManager`, dirty checking, `ObjectOptimisticLockingFailureException`) - the base has no JPA; aggregates are written by Spring Data JDBC `save(...)` and conflicts raise `OptimisticLockingFailureException`.
- Agent expects a modified aggregate to be written at commit without `save(...)` - Spring Data JDBC has no dirty checking.
- Agent lets a `Stream` outlive its transaction or reads it without try-with-resources - the transaction must span the consumer; set a fetch size.
- Agent calls `@Transactional` methods on `this` — self-invocation bypasses proxy
- Agent expects checked exceptions to rollback — must add `rollbackFor`
- Agent uses `@Transactional` on `private` methods — Spring proxy can't intercept
- Agent pulls in `spring-retry` for a new Boot 4 retry path - use core `@Retryable` and `@EnableResilientMethods`; preserve existing integrations when outside the task's scope.
- Agent relies on unspecified retry/transaction advisor ordering - verify every attempt gets a fresh transaction; a separate retry facade makes the boundary explicit.

## Official sources

- [Spring transaction propagation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [TransactionalEventListener lifecycle](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/event/TransactionalEventListener.html)
- [Spring Data JDBC — optimistic locking](https://docs.spring.io/spring-data/relational/reference/jdbc/entity-persistence.html#jdbc.entity-persistence.optimistic-locking)

## Provenance

Adapted for the Elysium Java base (JDK 25, Spring Boot 4, Spring Data JDBC + `JdbcClient`, PostgreSQL 18).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/transactional-patterns`, MIT License, © Rrezart Prebreza.
Changes: optimistic locking with Spring Data JDBC instead of JPA/Hibernate, streaming reads inside a
transaction, outbox in PostgreSQL instead of a broker, `Long` IDs.
