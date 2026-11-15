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
    ADD CONSTRAINT customer_order_customer_email_nn CHECK (customer_email IS NOT NULL) NOT VALID;
-- V00008 (separate migration): validate without blocking writes, then SET NOT NULL skips the scan
ALTER TABLE customer_order VALIDATE CONSTRAINT customer_order_customer_email_nn;
ALTER TABLE customer_order ALTER COLUMN customer_email SET NOT NULL;
-- V00009 (after no deployed code reads legacy_email)
ALTER TABLE customer_order DROP COLUMN legacy_email;

-- Index on a table with traffic: CONCURRENTLY, ALONE in its own file (Flyway rejects mixing
-- transactional and non-transactional statements), and transactional-lock=false (otherwise the
-- build waits forever on Flyway's own advisory-lock transaction).
-- V00006__index_customer_order_email.sql
CREATE INDEX CONCURRENTLY customer_order_customer_email_idx ON customer_order (customer_email);

-- Dangerous: RENAME a column running code uses; NOT NULL without default on a table with rows
```

A new table that no deployed code uses yet may get its indexes in the same migration without
`CONCURRENTLY`. Forward-only means an older release cannot run on a newer schema (ADR-00003): split
incompatible changes into expand, migrate, contract.

## Existing database without history

Keep `baseline-on-migrate: false`. For a database that already has tables: back it up, compare its
schema with the migrations, run a one-time `flyway baseline -baselineVersion=<verified version>`
against exactly that target, inspect `flyway info`, then migrate. Never baseline an unknown or partly
migrated schema to silence a startup error.

## Concurrent branches

Before merging, rebase and renumber your **unmerged** migration to the next free version; a merged
or deployed migration is never renumbered. Never `out-of-order: true`.
