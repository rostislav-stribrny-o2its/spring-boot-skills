-- Template for an initial schema migration.
-- Name the real file with a five-digit version, e.g. V00001__create_customer_order.sql
-- DDL style: see the design-postgres-tables skill.

-- Lookup table (reference data) instead of a PostgreSQL ENUM; values ship with the migration
CREATE TABLE order_status (
    id      smallint PRIMARY KEY,
    code    text NOT NULL UNIQUE CHECK (length(code) <= 30),
    name    text NOT NULL
);

INSERT INTO order_status (id, code, name) VALUES
    (1, 'PENDING',   'Pending'),
    (2, 'CONFIRMED', 'Confirmed'),
    (3, 'CANCELLED', 'Cancelled');

CREATE TABLE customer_order (
    -- IDs stay ≤ 2^53−1 so they are exact as JSON numbers in a JavaScript frontend
    id              bigint GENERATED ALWAYS AS IDENTITY (MAXVALUE 9007199254740991) PRIMARY KEY,
    customer_email  text            NOT NULL CHECK (length(customer_email) <= 254),
    order_status_id smallint        NOT NULL DEFAULT 1 REFERENCES order_status (id),
    total_amount    numeric(19, 2)  NOT NULL DEFAULT 0.00,
    notes           text,
    created_at      timestamptz     NOT NULL DEFAULT now(),
    updated_at      timestamptz     NOT NULL DEFAULT now(),
    version         bigint          NOT NULL DEFAULT 0      -- @Version (optimistic locking)
);

-- Child entity of the customer_order aggregate (Spring Data JDBC @MappedCollection
-- with idColumn = "order_id", keyColumn = "line_no" for a List)
CREATE TABLE order_item (
    order_id        bigint          NOT NULL REFERENCES customer_order (id) ON DELETE CASCADE,
    line_no         integer         NOT NULL,
    product_id      bigint          NOT NULL,
    product_name    text            NOT NULL CHECK (length(product_name) <= 200),
    quantity        integer         NOT NULL CHECK (quantity > 0),
    unit_price      numeric(19, 2)  NOT NULL CHECK (unit_price >= 0),
    PRIMARY KEY (order_id, line_no)
);

-- Always index foreign keys — PostgreSQL does not create these automatically
-- (order_item.order_id is covered by its primary key (order_id, line_no))
CREATE INDEX customer_order_order_status_id_idx ON customer_order (order_status_id);

-- Index commonly filtered/sorted columns
CREATE INDEX customer_order_customer_email_idx ON customer_order (customer_email);
CREATE INDEX customer_order_created_at_idx ON customer_order (created_at);
