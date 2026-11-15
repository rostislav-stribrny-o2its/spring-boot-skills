# Migration patterns (reference)

Part of `db-flyway-migrations`. Lock levels of single statements and large backfills:
`db-postgres-database-migration`.

## Safe migration patterns

```sql
-- Safe: nullable column, or a column with a constant default (no table rewrite)
ALTER TABLE customer_order ADD COLUMN notes text;
ALTER TABLE customer_order ADD COLUMN priority integer NOT NULL DEFAULT 0;

-- Safe: rename legacy_email → customer_email via expand → migrate → contract, across releases
-- V00005: add the new column (nullable) and backfill a SMALL table here; a large table is
--         backfilled in batches between releases (db-postgres-database-migration)
ALTER TABLE customer_order ADD COLUMN customer_email text;
UPDATE customer_order SET customer_email = legacy_email WHERE customer_email IS NULL;
-- V00007 (after every writer fills customer_email): enforce for new rows without a scan
ALTER TABLE customer_order
    ADD CONSTRAINT ck_customer_order_customer_email_nn CHECK (customer_email IS NOT NULL) NOT VALID;
-- V00008 (separate migration): validate without blocking writes, then SET NOT NULL skips the scan
ALTER TABLE customer_order VALIDATE CONSTRAINT ck_customer_order_customer_email_nn;
ALTER TABLE customer_order ALTER COLUMN customer_email SET NOT NULL;
-- V00009 (after no deployed code reads legacy_email)
ALTER TABLE customer_order DROP COLUMN legacy_email;

-- Index on a table with traffic: CONCURRENTLY, ALONE in its own file (Flyway rejects mixing
-- transactional and non-transactional statements), and transactional-lock=false (otherwise the
-- build waits forever on Flyway's own advisory-lock transaction).
-- V00006__index_customer_order_email.sql
CREATE INDEX CONCURRENTLY idx_customer_order_customer_email ON customer_order (customer_email);

-- Dangerous: RENAME a column running code uses; NOT NULL without default on a table with rows
```

A new table that no deployed code uses yet may get its indexes in the same migration without
`CONCURRENTLY`. Forward-only means an older release cannot run on a newer schema (rolling back production
is a database restore to a point in time, never a downgrade migration): split
incompatible changes into expand, migrate, contract.

## Environments

**Separate step in every environment (ADR-00008 point 12).** Migrations run exclusively via a
one-shot migration container (`dockerfiles/Dockerfile.db-migrate`, official Flyway image, pinned by
digest, running as non-root) or local migration script (`backend/scripts/db.sh migrate local|deploy`)
carrying the `db/migration` folders of both applications. It is the only holder of the owner password
and applies the required settings (default schema, locations, placeholders, validation), `integration`
first, then `backend`. Applications never hold the owner password and keep `spring.flyway.enabled: false`.
The deployment command runs: backup (PROD), stop, migrate, start, health check (ADR-00008 point 12);
where that command lives: open (decided by the architecture owner in `docs/admin-guide/operations/`).
Keep the Flyway CLI version equal to the Flyway version managed by Spring Boot, so both write the
same history table format. Reference: `dockerfiles/Dockerfile.db-migrate`, `backend/scripts/db.sh`.

**Dropping the schema only on LOCAL** (ADR-00008 point 11): reset by recreating the local database
volume, or Flyway `clean` from the command line against the local database. Never via application
configuration, never on DEV, TEST or PROD; how DEV and TEST are reset is open (decided by the
architecture owner, ADR-00008).

## Data migrations over existing rows

When a versioned migration modifies or backfills existing data (rather than pure DDL or static
reference rows), verify four safeguards before merge (`db-postgres-database-migration`):

1. **Batching and lock duration:** bound table lock duration on non-trivial tables (batched updates
   or `statement_timeout` / `lock_timeout` guards so production traffic is not stalled).
2. **Pre/post control counts:** assert expected row counts or control aggregates before and after
   the data transformation (fail the migration transaction immediately if invariants diverge).
3. **Forward-recovery path:** because modified data does not roll back once committed across
   releases, document and test the forward-recovery procedure.
4. **Synthetic or anonymized data only:** validate the migration against synthetic or anonymized
   data in Testcontainers — never copy raw production personal/sensitive data into test fixtures or
   AI context.

## Existing database without history

Keep `baseline-on-migrate: false`. For a database that already has tables: back it up, compare its
schema with the migrations, run a one-time `flyway baseline -baselineVersion=<verified version>`
against exactly that target, inspect `flyway info`, then migrate. Never baseline an unknown or partly
migrated schema to silence a startup error.

## Concurrent branches

Before merging, rebase and renumber your **unmerged** migration to the next free version; a merged
or deployed migration is never renumbered. Never `out-of-order: true`.
