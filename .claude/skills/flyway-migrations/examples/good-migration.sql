-- GOOD — V00004__add_shipping_address_to_customer_order.sql
-- Five-digit version, double underscore, nullable column, multi-step rename.
-- The index for shipping_address lives in its own file:
-- good-migration-concurrent-index.sql (V00005). CREATE INDEX CONCURRENTLY must not
-- share a migration with other statements — Flyway rejects mixed migrations.

-- Step 1: Add nullable column (safe for existing data — no rewrite)
ALTER TABLE customer_order ADD COLUMN shipping_address text;

-- Step 2: Multi-step column rename (safe approach)
-- Never rename a column that running code uses — it breaks code mid-deploy.
-- Instead: add new → backfill → deploy code reading both → drop old
-- (assumes a legacy full_name column from an earlier migration)
ALTER TABLE customer_order ADD COLUMN customer_name text;

-- Step 3: Backfill from old column
-- (large tables: batch it, see the postgres-database-migration skill)
UPDATE customer_order SET customer_name = full_name WHERE customer_name IS NULL;

-- Note: DROP COLUMN full_name goes in a LATER migration,
-- after all deployed code reads from customer_name
