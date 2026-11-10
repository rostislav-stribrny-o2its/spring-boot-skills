-- BAD — V4_add_column.sql
--    single underscore and a non-padded version: Flyway skips the file,
--    and versions must be five digits (V00004__…)

-- Adding NOT NULL column without default — fails on tables with existing rows
ALTER TABLE customer_order ADD COLUMN shipping_address text NOT NULL;

-- Direct column rename on a live table — breaks application code during rolling deploy
ALTER TABLE customer_order RENAME COLUMN full_name TO customer_name;

-- Creating index without CONCURRENTLY on a table with traffic — blocks writes
CREATE INDEX customer_order_order_status_id_idx ON customer_order (order_status_id);

-- Sample data in a migration — belongs in a @Profile("dev") seeder or in the test
-- (reference data of lookup tables is the exception: it DOES belong in V migrations)
INSERT INTO customer_order (customer_id, order_status_id) VALUES (1, 1);

-- PostgreSQL ENUM instead of a lookup table
CREATE TYPE order_state AS ENUM ('PENDING', 'CONFIRMED');

-- Modifying a previously-run migration (V00002) to "fix" it, or writing a U… undo file
-- NEVER do this — Flyway checksums fail in all deployed environments; fix forward instead
