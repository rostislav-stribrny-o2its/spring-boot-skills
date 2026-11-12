---
name: db-flyway-migrations
description: >
  Use when creating database migrations, schema changes, reference data (lookup tables),
  or any SQL that modifies database structure. Covers Flyway naming (five-digit versions),
  forward-only fixes, reference data in migrations, CI checks and safe migration patterns
  for PostgreSQL 18.
---

# Flyway Migrations

Flyway is the only tool that changes the schema. Hibernate/JPA schema generation is not used
(the stack has no JPA); nothing creates tables at application start except Flyway.

DDL style (types, keys, lookup tables, normalization) is defined by the `db-design-postgres-tables`
skill; this skill covers how a migration is named, written, checked and deployed. Lock levels of
individual DDL statements and large backfills are in `db-postgres-database-migration`.

## Dependencies

Boot's modular starters do NOT pull Flyway in transitively — a data starter alone means
migrations silently never run. Add the Flyway starter plus the PostgreSQL module:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-flyway</artifactId>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId> <!-- DB-specific module is required -->
</dependency>
```

## Location and File Naming

Migrations live in the application module that owns the schema:

```
<app-module>/src/main/resources/db/migration/

V<NNNNN>__<description>.sql     ← versioned, five digits, run once
R__<description>.sql            ← repeatable: views and other objects re-created as a whole

Examples:
V00001__create_customer.sql
V00002__create_order.sql
V00003__add_shipping_address_to_order.sql
R__order_summary_view.sql
```

Rules:
- Version is always **five digits with leading zeros** (`V00042__`), so files sort correctly
  in every tool. Flyway reads `00042` as version 42.
- Double underscore `__` between version and description; `_` for spaces in the description.
- Versions are sequential; never go back and fill gaps.
- **No undo migrations.** `U…` files are not used. A mistake is fixed by a new forward
  migration (`V00043__fix_…`), never by editing or reverting the deployed one.
- **A migration that has run in any shared environment is never modified** — not even a comment
  (the checksum changes and every environment fails validation).
- `R__` only for objects that are dropped and re-created as a whole (views, functions used by
  views). Tables, columns, constraints and data always go into `V` migrations.

## Example Migrations

```sql
-- V00001__create_order_status.sql — lookup table instead of a PostgreSQL ENUM
CREATE TABLE order_status (
    id          smallint PRIMARY KEY,           -- stable, assigned in the migration
    code        text NOT NULL UNIQUE CHECK (length(code) <= 30),
    name        text NOT NULL
);

INSERT INTO order_status (id, code, name) VALUES
    (1, 'PENDING',   'Pending'),
    (2, 'CONFIRMED', 'Confirmed'),
    (3, 'CANCELLED', 'Cancelled');

-- V00002__create_customer_order.sql
CREATE TABLE customer_order (
    id              bigint GENERATED ALWAYS AS IDENTITY (MAXVALUE 9007199254740991) PRIMARY KEY,
    customer_id     bigint NOT NULL REFERENCES customer (id),
    order_status_id smallint NOT NULL REFERENCES order_status (id),
    total_amount    numeric(19, 2) NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         bigint NOT NULL DEFAULT 0   -- optimistic locking (@Version)
);

CREATE INDEX customer_order_customer_id_idx ON customer_order (customer_id);
CREATE INDEX customer_order_order_status_id_idx ON customer_order (order_status_id);

-- V00003__add_shipping_address_to_customer_order.sql
-- Adding a column — nullable or with a default (safe for existing rows)
ALTER TABLE customer_order
    ADD COLUMN shipping_address text,
    ADD COLUMN shipped_at timestamptz;
```

Why these types: IDs are `bigint` with an upper bound of 2^53−1 (`MAXVALUE 9007199254740991`)
so that they stay exact as JSON numbers in a JavaScript frontend; money and other exact
decimals are `numeric(p, s)` (mapped to `BigDecimal`), never `real`/`double precision`. Details
in `db-design-postgres-tables`.

## Reference Data vs. Development Data

- **Reference data** (lookup tables, codes the application logic relies on) is part of the schema:
  it goes into versioned `V` migrations, with stable explicit IDs. Without it, foreign keys to the
  lookup table fail on an empty database. Changing reference data = a new `V` migration.
- **Development and test data** (sample customers, demo orders, test accounts) never goes into
  migrations. It is loaded by a profile-bound seeder (`@Profile("dev")`, see
  `templates/DevDataSeeder.java`) or by the test itself.

## Safe Migration Patterns

```sql
-- Safe: add nullable column
ALTER TABLE customer_order ADD COLUMN notes text;

-- Safe: add column with a constant default (no table rewrite)
ALTER TABLE customer_order ADD COLUMN priority integer NOT NULL DEFAULT 0;

-- Safe: rename legacy_email → customer_email via expand → migrate → contract, across deploys
-- (illustrative version numbers; the CONCURRENTLY index below is V00006)
-- Step 1 (V00005): add new column (nullable — old instances keep inserting without it)
ALTER TABLE customer_order ADD COLUMN customer_email text;
-- Step 2 (V00005): backfill — only a small table in the migration itself; a large table is
-- backfilled in batches by an application job between releases (a migration file is ONE
-- transaction; see postgres-database-migration). Deployed code writes both columns meanwhile.
UPDATE customer_order SET customer_email = legacy_email WHERE customer_email IS NULL;
-- Step 3 (V00007, only after EVERY writer populates customer_email): add the check as
-- NOT VALID — instant, no table scan, but new rows are already enforced
ALTER TABLE customer_order
    ADD CONSTRAINT customer_order_customer_email_nn CHECK (customer_email IS NOT NULL) NOT VALID;
-- Step 4 (V00008 — a SEPARATE migration, so the V00007 lock is already released): validate
-- without blocking writes, then SET NOT NULL reuses the validated check and skips the scan
ALTER TABLE customer_order VALIDATE CONSTRAINT customer_order_customer_email_nn;
ALTER TABLE customer_order ALTER COLUMN customer_email SET NOT NULL;
-- Step 5 (V00009, after no deployed code reads legacy_email): drop it
ALTER TABLE customer_order DROP COLUMN legacy_email;

-- Safe: add index CONCURRENTLY (no write lock) — TWO rules, both required:
-- 1. Put it ALONE in its own migration file. Flyway detects CONCURRENTLY as
--    non-transactional; mixed with any other statement the migration is rejected:
--    "Detected both transactional and non-transactional statements".
-- 2. Set spring.flyway.postgresql.transactional-lock=false. Flyway's default
--    advisory lock holds an open transaction on a second connection, and
--    CONCURRENTLY waits for every open transaction — including Flyway's own.
--    The migration hangs forever. Session-level locks still block concurrent migrators.
-- V00006__index_customer_order_email.sql (after V00005 adds the column)
CREATE INDEX CONCURRENTLY customer_order_customer_email_idx ON customer_order (customer_email);

-- Dangerous: rename column directly while old code is running (breaks the running app);
--    a direct RENAME is only acceptable when no deployed code uses the column yet
ALTER TABLE customer_order RENAME COLUMN customer_id TO buyer_id;

-- Dangerous: NOT NULL without default on a table with rows
ALTER TABLE customer_order ADD COLUMN priority integer NOT NULL; -- fails on existing rows
```

A new table that no deployed code uses yet may get its indexes in the same migration without
`CONCURRENTLY` (there is nothing to block). `CONCURRENTLY` is for tables that already carry
traffic.

## application.yml

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false  # reject non-empty schemas without migration history
    validate-on-migrate: true
    out-of-order: false         # enforce sequential execution
    clean-disabled: true        # never drop the schema from the application
    postgresql:
      transactional-lock: false # required for CREATE INDEX CONCURRENTLY (see above)
```

`clean-disabled: true` is the default in Flyway and stays `true` in every shared environment
(DEV servers, TEST, PROD). `flyway clean` is allowed only against a local throw-away database,
from the command line, never from application configuration.

## CI Checks

Every pull request that touches `db/migration` runs:

1. **Duplicate and format check** — every file matches `V\d{5}__[a-z0-9_]+\.sql` or
   `R__[a-z0-9_]+\.sql`, and no version number appears twice. Two branches that each add
   `V00042__` pass review separately and collide after merge; this check catches it.
2. **`flyway validate`** against the migrations of the target branch.
3. **Migrate an empty database** — all migrations from `V00001` on a fresh PostgreSQL 18
   (Testcontainers, same major version as production).
4. **Migrate from the last release** — restore the schema of the last released version
   (migrate to its last version, or start from its dump), then apply the new migrations.
   This is the path production takes.
5. Integration tests run against the migrated PostgreSQL in Testcontainers
   (see `be-spring-testing-persistence`).

## Onboarding an existing database

Keep automatic baselining disabled in normal application configuration. For a database
that already has tables but no Flyway history:

1. Back up the database and verify the target URL, database, schema and credentials.
2. Compare its schema with the migrations and explicitly select the version already present.
3. Run a one-time `flyway baseline -baselineVersion=<verified-version>` using that target.
4. Inspect `flyway info`, then migrate only the later versions. Never baseline an unknown
   or partially migrated schema just to silence a startup error.

This is different from a baseline migration script for new environments. Automatic
baselining removes Flyway's wrong-database safety check:
[official baseline-on-migrate documentation](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting).

## Development Data (dev profile only)

```java
// Development data via a profile-bound seeder; reference data belongs in V migrations
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {
    private final CustomerRepository customerRepository;

    public DevDataSeeder(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (customerRepository.count() == 0) {
            customerRepository.save(Customer.create("dev.customer@example.test"));
        }
    }
}
```

## Team Workflow: Concurrent Migrations

- Two developers adding migrations at the same time will pick the same next version.
- Before merging, rebase onto the target branch and renumber your **unmerged** migration to the
  next free version. A merged or deployed migration is never renumbered.
- The CI duplicate check (above) is the safety net; `flyway info` locally shows gaps and
  duplicates before committing.
- Never set `out-of-order: true` in a shared environment — it masks migration ordering bugs.

## Gotchas

- Agent names files `V1__…` or `V2.1__…` — versions are five digits: `V00001__`, `V00002__`
- Agent names files `V00001_create_users.sql` (single underscore) — must be double `__`
- Agent writes a `U…` undo migration or "reverts" by editing — fix forward with a new `V` migration
- Agent modifies an existing migration file — never edit a migration that has run
- Agent uses `uuid`, `serial` or unbounded `bigint` identity for IDs — `bigint GENERATED ALWAYS AS IDENTITY (MAXVALUE 9007199254740991)`
- Agent uses `CREATE TYPE … AS ENUM` or a free-text status column — lookup table with FK
- Agent puts lookup values into a dev seeder — reference data belongs in `V` migrations
- Agent puts sample/test data into a migration — dev seeder or the test itself
- Agent adds `NOT NULL` column without default — use nullable or provide a default
- Agent renames columns directly on a live table — expand/contract across deploys
- Agent mixes `CREATE INDEX CONCURRENTLY` with other statements in one migration — Flyway rejects it ("both transactional and non-transactional statements"); give it its own file
- Agent ships a `CONCURRENTLY` migration without `spring.flyway.postgresql.transactional-lock=false` — the index build waits on Flyway's own advisory-lock transaction and deploys hang
- Agent skips indexes — always index foreign keys and columns used in WHERE/ORDER BY
- Agent puts business logic into triggers or functions — business logic lives in the application `@Service` layer; the database holds structure and declarative constraints
- Agent creates a migration with `DROP TABLE` or `DROP COLUMN` as first step — add the new structure, deploy code, then drop the old in a later migration
- Agent adds only `flyway-core` — database support lives in `flyway-database-<db>` modules; without it startup fails with "Unsupported Database"
- Migrating from Boot 3: `flyway-core` alone used to activate migrations because the auto-configuration lived in `spring-boot-autoconfigure`; Boot 4 moved it to `spring-boot-flyway`, so without `spring-boot-starter-flyway` migrations silently never run

## Provenance

Adapted for the Elysium Java base (JDK 25, Spring Boot 4, PostgreSQL 18, Spring Data JDBC).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/flyway-migrations`, MIT License, © Rrezart Prebreza.
Changes: five-digit versions, no undo, reference data in migrations, `clean-disabled`, CI checks,
bounded `bigint` IDs and lookup tables in examples.
