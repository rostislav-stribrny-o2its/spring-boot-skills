---
name: db-flyway-migrations
description: >
  PostgreSQL/Flyway: use when creating migrations, schema changes, reference data or fictitious
  LOCAL/DEV data, or when configuring how migrations run per environment (at start on LOCAL/DEV,
  a separate step on TEST/PROD, owner vs. runtime login, search_path, grants). Covers five-digit
  naming, forward-only fixes, CI checks and safe migration patterns for PostgreSQL 18.
---

# Flyway Migrations

Flyway is the only tool that changes the schema (ADR-00008 point 1). Nothing derives DDL from Java;
the stack has no JPA. DDL style (types, keys, lookup tables) is in `db-design-postgres-tables`; lock
levels of single statements and large backfills in `db-postgres-database-migration`; safe change
patterns, baselining and concurrent branches in [migration-patterns.md](migration-patterns.md).

## Dependencies

Boot 4's modular starters do NOT pull Flyway in transitively — without the Flyway starter migrations
silently never run (both are in the application template of `be-maven-reactor`):

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

## Schemas, roles, order

- Each application owns its schema and its own migration history (ADR-00008 point 11). Phase 1 has
  **one backend schema** (modules are packages, not schemas — ADR-00008 point 12; its name is set in
  `docs/design/database/`, `backend` in the examples); `scheduler-service` owns schema `integration`.
- Per schema: an **owner role** that only migrates, and a **runtime role** the application connects
  with, which cannot change the schema.
- **The database and the roles are created by the one-time DB admin script**, never by a migration.
  Migrations grant the runtime role its rights (below).
- Order: `integration` migrations, then backend migrations, then both applications (ADR-00008 point 12).
  On LOCAL and DEV the start order of the applications keeps it.

**`search_path` — one rule for every login:** each application's logins see **its own schema**
first, so its own tables are written **unqualified** everywhere (SQL, `@Table`, migrations); a table
of the **other** application's schema is always **qualified** (`integration.run`,
`backend.integration_control`).

- runtime login: `spring.datasource.hikari.schema: <own schema>` (Hikari sets it on every
  connection; PostgreSQL's default `"$user", public` would find nothing);
- owner login: Flyway `default-schema: <own schema>` (Flyway sets the `search_path` while it
  migrates); the separate migration step uses the same setting;
- an `*IT` connected as the runtime role reads a table of each migration to prove it (CI check 5).

## Migrations per environment

| Environment | When migrations run | Login | Drop schema |
|---|---|---|---|
| LOCAL | at application start (Spring Boot Flyway) | owner (`spring.flyway.user`) | allowed — locally only |
| DEV (reference stack and branch stacks) | at application start | owner | never |
| TEST | separate step **before** start | owner, known **only** to that step | never |
| PROD | separate step before start, **after a backup** | owner, only that step | never |

The application always runs with the runtime login (`spring.datasource.*`). On LOCAL and DEV the owner
password is additionally in the application's environment, used only by Flyway; on TEST and PROD it is
not there at all (ADR-00008 point 12).

```yaml
spring:
  datasource:                                   # runtime login: cannot change the schema
    url: ${DB_URL}
    username: ${DB_APP_USER}
    password: ${DB_APP_PASSWORD}
    hikari:
      schema: backend                           # search_path of the runtime login = own schema
  flyway:
    enabled: ${DB_MIGRATE_ON_START:false}       # true only on LOCAL and DEV; default off = safe
    user: ${DB_OWNER_USER:}                     # owner login; empty = Flyway would use the datasource
    password: ${DB_OWNER_PASSWORD:}             #   login, which is harmless only because enabled=false there
    schemas: backend                            # schema name per docs/design/database
    default-schema: backend                     # search_path of the owner login while migrating
    create-schemas: false                       # the admin script prepares database and roles
    locations: ${DB_MIGRATION_LOCATIONS:classpath:db/migration}
    placeholders:
      runtime_role: ${DB_APP_USER}
    baseline-on-migrate: false                  # reject non-empty schemas without history
    validate-on-migrate: true
    validate-migration-naming: true
    out-of-order: false
    clean-disabled: true                        # the application never drops the schema
    postgresql:
      transactional-lock: false                 # required for CREATE INDEX CONCURRENTLY
```

`scheduler-service` uses the same block with `integration` in place of `backend`. Wherever
`DB_MIGRATE_ON_START=true`, `DB_OWNER_USER`/`DB_OWNER_PASSWORD` must be set too.

**Separate step on TEST and PROD.** A one-shot migration container (official Flyway image, pinned by
digest, running as non-root) carrying the `db/migration` folders of both applications. It is the only
holder of the owner password and applies the same settings as above (default schema, locations,
placeholders, validation). The deployment command runs: backup (PROD), stop, migrate, start, health
check (ADR-00008 point 12); where that command lives: open (decided by the architecture owner in
`docs/admin-guide/operations/`). Keep the Flyway CLI version equal to the Flyway version managed by
Spring Boot, so both write the same history table format.

**Dropping the schema only on LOCAL** (ADR-00008 point 11): reset by recreating the local database
volume, or Flyway `clean` from the command line against the local database. Never via application
configuration, never on DEV, TEST or PROD; how DEV and TEST are reset is open (decided by the
architecture owner, ADR-00008).

## Grants in migrations

Migrations run as the owner, so default privileges cover every later table of the owner:

```sql
-- V00001__grant_runtime_role.sql   (${flyway:defaultSchema} = the own schema)
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO ${runtime_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${runtime_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT USAGE, SELECT ON SEQUENCES TO ${runtime_role};
```

Role and schema names come from placeholders (they differ per environment), never literals.
Narrower rights (append-only, read-only for support views) are granted explicitly in the migration
that creates the table. Backend migrations also grant the integration service read access to the
control tables they create (ADR-00008 point 12).

## Location and File Naming

```
<app-module>/src/main/resources/db/migration/

V<NNNNN>__<description>.sql     ← versioned, five digits, run once
R__<description>.sql            ← repeatable: views and objects re-created as a whole

V00001__grant_runtime_role.sql
V00002__create_order_status.sql
V00003__create_customer.sql
V00004__create_customer_order.sql
R__order_summary_view.sql
```

- Version is always **five digits with leading zeros** (`V00042__`) so files sort correctly in every
  tool; Flyway reads `00042` as version 42.
- Double underscore `__` between version and description; `_` for spaces in the description.
- Versions are sequential; never go back and fill gaps.
- **Forward only** (ADR-00008 point 11): no `U…` undo files. A mistake is fixed by a new migration
  (`V00043__fix_…`), never by editing or reverting the deployed one.
- **A migration that has run in any shared environment is never modified** — not even a comment
  (the checksum changes and every environment fails validation).
- `R__` only for objects dropped and re-created as a whole (views, functions used by views). Tables,
  columns, constraints and reference data always go into `V` migrations.
- The ShedLock table of an application is an ordinary `V` migration of that application, in its own
  schema (DDL in `be-scheduling-shedlock`).

## Example Migrations

```sql
-- V00002__create_order_status.sql — lookup table instead of a PostgreSQL ENUM
CREATE TABLE order_status (
    id          smallint PRIMARY KEY,           -- stable, assigned in the migration
    code        text NOT NULL UNIQUE CHECK (length(code) <= 30),
    name        text NOT NULL
);

INSERT INTO order_status (id, code, name) VALUES
    (1, 'PENDING',   'Pending'),
    (2, 'CONFIRMED', 'Confirmed'),
    (3, 'CANCELLED', 'Cancelled');

-- V00003__create_customer.sql
CREATE TABLE customer (
    id          bigint GENERATED ALWAYS AS IDENTITY (MAXVALUE 9007199254740991) PRIMARY KEY,
    name        text NOT NULL,
    version     bigint NOT NULL DEFAULT 0
);

-- V00004__create_customer_order.sql
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
```

Table names are unqualified: Flyway's `default-schema` puts them into the own schema. IDs are
`bigint` bounded by 2^53−1 so they stay exact as JSON numbers; exact decimals are `numeric(p, s)`.
Details in `db-design-postgres-tables`. (The audit columns `created_by`/`updated_by` of ADR-00008
point 15 are left out of the examples for brevity.)

## Data migrations over existing rows

When a versioned migration modifies or backfills existing data (rather than pure DDL or static reference rows), verify four safeguards before merge (`db-postgres-database-migration`):
1. **Batching and lock duration:** bound table lock duration on non-trivial tables (batched updates or `statement_timeout` / `lock_timeout` guards so production traffic is not stalled).
2. **Pre/post control counts:** assert expected row counts or control aggregates before and after the data transformation (fail the migration transaction immediately if invariants diverge).
3. **Forward-recovery path:** because modified data does not roll back once committed across releases, document and test the forward-recovery procedure.
4. **Synthetic or anonymized data only:** validate the migration against synthetic or anonymized data in Testcontainers — never copy raw production personal/sensitive data into test fixtures or AI context.

## Reference data vs. fictitious data

- **Reference data** (lookup tables, codes the logic relies on) is part of the schema: versioned `V`
  migrations with stable explicit IDs, in every environment. Changing it = a new `V` migration.
- **Fictitious static data** (persons and records to work with right after the first start) exists
  **only on LOCAL and on DEV branch stacks** — never on TEST, PROD or the DEV reference stack, which
  reads a source copy and whose synchronisation would suspend fictitious persons (ADR-00008 point 16).

```
<app-module>/src/main/resources/db/testdata/
R__testdata_10_persons.sql
R__testdata_20_projects.sql
```

- Separate repeatable scripts, enabled only by
  `DB_MIGRATION_LOCATIONS=classpath:db/migration,classpath:db/testdata` on LOCAL and DEV branch stacks.
  Flyway runs repeatables **after** all versioned migrations and re-runs a script when its content
  changes.
- **Idempotent**: upsert by natural key (`INSERT … ON CONFLICT (personal_number) DO UPDATE …`), look
  up foreign keys by natural key in a subselect, never hard-code identity values, never delete rows a
  user created. Running a script twice yields the same state.
- A `V` migration that changes a table used by a test-data script updates the script in the same change
  (the script is re-run and must still apply).
- **In step with the Keycloak test accounts** (ADR-00004 point 8): every fictitious person carries the
  personal number of a fictitious test account, from the reserved test range; never a real one. The
  range itself: open (decided by the architecture owner; to be written in `docs/design/database/`).
- **CI check**: the configuration templates delivered for TEST and PROD neither enable migrations at
  start nor list `db/testdata`, and the migration-step image keeps `DB_MIGRATE_TESTDATA=false` by
  default (never enabled on TEST or PROD).
- Tests never rely on this data; they create their own (`be-testing-conventions`).

## CI Checks

Every pull request that touches `db/migration` or `db/testdata` runs:

1. **Format and duplicates** — every file matches `V\d{5}__[a-z0-9_]+\.sql` or `R__[a-z0-9_]+\.sql`,
   and no version appears twice. Two branches each adding `V00042__` collide only after merge.
2. **`flyway validate`** against the migrations of the target branch.
3. **Empty database** — all migrations from `V00001` on PostgreSQL 18 in Testcontainers, as owner,
   with the runtime role existing (as the admin script creates it); then the test-data scripts twice
   (idempotence).
4. **Upgrade from the last release** — migrate to the last released version, then apply the new ones.
5. Integration tests against the migrated database (`be-spring-testing-persistence`), connected as the
   runtime role where the test checks rights and the `search_path`.
6. The TEST/PROD configuration check from "Reference data vs. fictitious data".

## Gotchas

- Agent names files `V1__…`, `V2.1__…` or with a single `_` — five digits and `__`: `V00001__`
- Agent writes an undo migration or edits a deployed one — fix forward with a new `V` migration
- Agent lets the application connect as the schema owner — runtime role in `spring.datasource`, owner only in `spring.flyway.user`
- Agent leaves the runtime `search_path` at the default — `spring.datasource.hikari.schema`; otherwise "relation does not exist"
- Agent qualifies its own tables with the schema, or leaves the other application's tables unqualified — own unqualified, foreign qualified
- Agent enables migrations at start on TEST/PROD or puts the owner password into their app configuration — separate step only
- Agent creates roles or the database in a migration — the DB admin script does that
- Agent hard-codes role or schema names in grants — `${runtime_role}`, `${flyway:defaultSchema}`
- Agent sets `clean-disabled: false` in application configuration — dropping only locally, from the command line
- Agent puts fictitious persons into a `V` migration or a Java seeder — `db/testdata/R__testdata_*.sql`, LOCAL and DEV branch stacks only
- Agent writes a test-data script that fails or duplicates on the second run — upsert by natural key
- Agent uses a real personal number in fictitious data — reserved test range, matching the Keycloak test accounts
- Agent puts lookup values anywhere but `V` migrations — reference data ships with the schema
- Agent mixes `CREATE INDEX CONCURRENTLY` with other statements, or omits `transactional-lock: false` — rejected or hangs
- Agent puts business logic into triggers or functions — business logic lives in the services (ADR-00008 point 3)
- Agent adds only `flyway-core` — needs `spring-boot-starter-flyway` and `flyway-database-postgresql`
- Agent uses a Flyway CLI version different from the Boot-managed one in the migration step

## Provenance

Adapted for this application's Java base (JDK 25, Spring Boot 4, PostgreSQL 18, Spring Data JDBC).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/flyway-migrations`, MIT License, © Rrezart Prebreza (see `LICENSE.txt`).
Changes: five-digit versions, no undo, reference data in migrations, `clean-disabled`, CI checks,
bounded `bigint` IDs and lookup tables; migrations per environment (start vs. separate step, owner vs.
runtime login, `search_path`, grants by placeholder), fictitious data as idempotent repeatable scripts
on LOCAL and DEV branch stacks only.
