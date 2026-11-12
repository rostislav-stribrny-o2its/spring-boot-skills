---
name: be-idempotency-patterns
description: >
  Java/Spring backend: use when making retried HTTP commands or message processing safe against duplicate effects, including database-backed request keys, payload conflicts and concurrent retries. Do not apply caching as a substitute for business idempotency.
---

# Idempotency Patterns

## Spring Boot 4 baseline

The examples target JDK 25, Spring Boot 4 and PostgreSQL 18; database access is `JdbcClient`/Spring Data JDBC on one
`DataSourceTransactionManager`. Keep dependencies managed by the project's Boot BOM.

## Define the contract

Identify the authenticated principal, operation, idempotency key, canonical request hash,
retention window and replayable result. Scope uniqueness by principal and operation;
a raw client key must never allow reading another user's result. Authenticate and authorize
before both initial execution and replay. Bound key length and stored response size.
Document what same-key/different-payload and still-in-progress requests return.

## Database-local effects

For PostgreSQL, [the schema and claim example](examples/good-idempotency.sql) uses a composite
primary key and INSERT ON CONFLICT DO NOTHING RETURNING. In a single database transaction:
claim the key, apply the business write, store the resulting status/body/selected headers,
then commit. Roll back the claim with the business mutation on failure.

If the insert returns no row, load the existing result in a subsequent statement under READ
COMMITTED, compare the canonical hash and replay only when it matches. A concurrent insert
waits on the uniqueness constraint; bound lock waits and return a documented retryable outcome
on timeout. With snapshot isolation, serialization failures require retrying the whole transaction.
Do not catch a unique-constraint violation and continue in an already-aborted transaction.

A result row and business write must use the same transaction manager and database. Return
the stored result rather than reconstructing it from mutable current entity state.
Keep replay headers allowlisted; do not persist cookies, bearer tokens or hop-by-hop headers.

## External effects and messages

A database transaction cannot atomically include an HTTP payment or email. Write an outbox record
(a row in a job table in PostgreSQL) with the business mutation and let a retryable worker —
a `@Scheduled` poller claiming rows with `FOR UPDATE SKIP LOCKED` (safe on all replicas, ShedLock
optional; see `be-pg-job-queue`) — perform the external effect using the provider's idempotency support. The base has no message broker. Reconcile uncertain outcomes before retrying irreversible effects.
For queued work (job/outbox rows), record a unique job/processor pair and apply the effect in one transaction;
mark the row done only in that commit. Retry failed rows with backoff and move rows that keep failing to a
`failed` status with the error, instead of retrying forever.

Define retention from the real client retry window. Deleting a key permits a later repeat to
execute again; do not present TTL as an exactly-once guarantee. Protect stored responses as
application data and delete them according to the project's retention policy.

## Verification

Test simultaneous identical requests, conflicting payloads, principal separation, rollback after
claim, response replay after a lost connection and retries following a worker crash.
Use PostgreSQL in Testcontainers (same major version as production) for lock and isolation tests, not H2.
[The bad example](examples/bad-idempotency.java) illustrates the check-then-act race.

## Gotchas

- Agent checks existence then inserts - enforce uniqueness in the database.
- Agent uses a JVM map or Redis TTL as the only business guard - coordinate with the actual write transaction.
- Agent replays another principal's result - scope the key and re-authorize.
- Agent uses `uuid` keys, `varchar(n)` or a tenant column copied from examples - follow `db-design-postgres-tables`: `text` with a length CHECK, `bigint` IDs; the base has no tenants.
- Agent retries an external effect after a timeout blindly - reconcile or use provider idempotency.
- Agent deletes keys too early - document the retry window and expiry semantics.

## Official sources

- [PostgreSQL INSERT and ON CONFLICT](https://www.postgresql.org/docs/current/sql-insert.html)
- [PostgreSQL transaction isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- [Spring transaction management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)

## Provenance

Adapted for the Elysium Java base (JDK 25, Spring Boot 4, Spring Data JDBC + `JdbcClient`, PostgreSQL 18).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/idempotency-patterns`, MIT License, © Rrezart Prebreza.
Changes: scoped by principal (no tenants), outbox as a PostgreSQL job table without a broker, DDL types
per `db-design-postgres-tables`.
