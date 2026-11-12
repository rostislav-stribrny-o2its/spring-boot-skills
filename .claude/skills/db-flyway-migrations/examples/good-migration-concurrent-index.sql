-- GOOD — V00005__index_customer_order_shipping_address.sql
-- CREATE INDEX CONCURRENTLY builds the index without blocking writes on PostgreSQL.
-- Two requirements:
--   1. This statement is ALONE in the file. Flyway runs it outside a transaction;
--      adding any other statement makes Flyway reject the whole migration.
--   2. spring.flyway.postgresql.transactional-lock=false — otherwise the index
--      build waits forever on Flyway's own advisory-lock transaction.
-- If a CONCURRENTLY build fails midway it leaves an INVALID index behind:
-- drop it in a NEW migration (DROP INDEX CONCURRENTLY, alone in its file) and
-- re-create it in the next one, rather than editing this file.

CREATE INDEX CONCURRENTLY customer_order_shipping_address_idx
    ON customer_order (shipping_address)
    WHERE shipping_address IS NOT NULL;
