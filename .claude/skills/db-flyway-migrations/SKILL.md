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
  - The `integration` migrations create only the service's own tables (runs, change records,
    mirror, staging, view state, ShedLock).
  - The **backend** migrations create the control tables in the backend schema (example:
    `integration_control`) and `GRANT USAGE ON SCHEMA` + `GRANT SELECT` on them to the scheduler's
    runtime role (placeholder `${scheduler_role}`); that role must already exist (admin script).
  - So on the very first start nothing references a table that does not exist yet: the scheduler
    reads `backend.<control table>` only at its ticks, and treats a missing table as "no view to
    read" (`be-scheduler-service`).
- How the reference implementation runs this on LOCAL and in CI: one script/container that migrates
  `integration`, then `backend`, then (LOCAL only) the test data — `backend/scripts/db.sh migrate
  local|deploy` and the `db-migrate` image (`dockerfiles/Dockerfile.db-migrate`), started before both
  applications. Read those files in the repository before changing the order.

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
    locations: classpath:db/migration           # LOCAL profile adds classpath:db/testdata (below)
    placeholders:
      runtime_role: ${DB_APP_USER}
      scheduler_role: ${DB_SCHEDULER_USER}      # backend only: grants on the control tables
    baseline-on-migrate: false                  # reject non-empty schemas without history
    validate-on-migrate: true
    validate-migration-naming: true
    out-of-order: false
    clean-disabled: true                        # the application never drops the schema
    postgresql:
      transactional-lock: false                 # required for CREATE INDEX CONCURRENTLY
```

Variable names above are illustrative; take the real ones from `backend/.env.example` and each
application's `application.yaml` (example: runtime logins `DB_API_USER` for
`backend-service` and `DB_SCHEDULER_USER` for `scheduler-service`, owner `DB_OWNER_USER`).
`scheduler-service` uses the same block with `integration` in place of `backend`. Wherever
`DB_MIGRATE_ON_START=true`, `DB_OWNER_USER`/`DB_OWNER_PASSWORD` must be set too.

**Separate step on TEST and PROD**: a one-shot, non-root migration container (official Flyway image
pinned by digest, Flyway CLI version = the Boot-managed one) with the `db/migration` folders of both
applications, the only holder of the owner password; deployment = backup (PROD), stop, migrate,
start, health check. **Dropping the schema only on LOCAL**, by recreating the database volume or
`flyway clean` from the command line. Details: [migration-patterns.md](migration-patterns.md),
"Environments".

## Grants in migrations

Migrations run as the owner, so default privileges cover every later table of the owner:

```sql
-- V00001__grant_runtime_role.sql   (${flyway:defaultSchema} = the own schema; same file in both applications)
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO "${runtime_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "${runtime_role}";
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT USAGE, SELECT ON SEQUENCES TO "${runtime_role}";
```

Role and schema names come from placeholders (they differ per environment), never literals; role
names are quoted. The default covers what both runtime roles need (the ShedLock table needs
`SELECT, INSERT, UPDATE`; staging, mirror and run state in `integration` need all four). Narrower
rights are taken away or granted explicitly in the migration that creates the table:

```sql
-- integration: change records are append-only for the runtime role (ADR-00008 point 12)
REVOKE UPDATE, DELETE ON integration_change_records FROM "${runtime_role}";
-- + a BEFORE UPDATE OR DELETE trigger that raises an exception (immutability guard, allowed)
-- deletion after the retention period: a separate cleanup role, GRANT DELETE explicitly

-- backend: the scheduler may read the control tables it is driven by
GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO "${scheduler_role}";
GRANT SELECT ON TABLE integration_control TO "${scheduler_role}";
```

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

The example set (`order_status`, `customer`, `customer_order`, `customer_note`, with the standard
columns, `ON DELETE` actions and index names) is the one in `db-design-postgres-tables`, "Example";
its file names are those listed above. Table names are unqualified: Flyway's `default-schema` puts
them into the own schema. A new migration takes the next free number after the highest existing
`V` file of that application (gaps are never filled).

## Data migrations over existing rows

A migration that modifies or backfills existing rows needs four safeguards before merge (bounded
locks/batches, pre/post control counts, a forward-recovery path, synthetic data only):
[migration-patterns.md](migration-patterns.md), "Data migrations over existing rows".

## Reference data vs. fictitious data

- **Reference data** (lookup tables, codes the logic relies on) is part of the schema: versioned `V`
  migrations with stable explicit IDs, in every environment. Changing it = a new `V` migration.
- **Fictitious static data** (persons and records to work with right after the first start) exists
  **only on LOCAL and on DEV branch stacks** — never on TEST, PROD or the DEV reference stack, which
  reads a source copy and whose synchronisation would suspend fictitious persons (ADR-00008 point 16).

```
<app-module>/src/main/resources/db/testdata/
R__testdata_10_persons.sql
R__testdata_20_customers.sql
R__testdata_30_customer_notes.sql
R__testdata_90_demo.sql
```

- **Numbering** in steps of ten by dependency: a script only references data of lower numbers
  (persons first, `90` for loose demo data). A new script takes the next free ten after the scripts
  it depends on. Flyway runs repeatables **after** all versioned migrations, in name order, and
  re-runs a script when its content changes.
- **Enabled only on LOCAL and DEV branch stacks**, by adding the location `db/testdata`:
  - at application start: the LOCAL profile, `application-local.yaml` →
    `spring.flyway.locations: classpath:db/migration,classpath:db/testdata`;
  - in the separate migration step: `DB_MIGRATE_TESTDATA=true` (the `db-migrate` image and
    `scripts/db.sh migrate local` add the folder; default `false`).
  Check the repository (`application-local.yaml`, `docker-compose.yml`, `backend/.env.example`) for
  which of the two it uses; never add a third switch.
- **Idempotent**: upsert by natural key (`INSERT … ON CONFLICT (personal_number) DO UPDATE …`), look
  up foreign keys by natural key in a subselect, never hard-code identity values, never delete rows a
  user created. Running a script twice yields the same state.
- **A table without a natural key** (example: `customer_note`): insert only when the same row does
  not exist yet, identified by the parent's natural key plus the row's own content; no unique
  constraint is added just for test data:

  ```sql
  INSERT INTO customer_note (customer_id, note)
  SELECT c.id, v.note
  FROM (VALUES ('jan.novak@example.com', 'Prefers e-mail contact')) AS v(email, note)
  JOIN customer c ON LOWER(c.email) = v.email
  WHERE NOT EXISTS (SELECT 1 FROM customer_note n WHERE n.customer_id = c.id AND n.note = v.note);
  ```
- A `V` migration that changes a table used by a test-data script updates the script in the same change
  (the script is re-run and must still apply).
- **In step with the Keycloak test accounts** (fictitious test accounts exist on LOCAL, DEV and TEST,
  never on PROD; a person is identified by personal number, never by e-mail or login): every
  fictitious person carries the personal number of a fictitious test account; never a real one.
  Use the range the repository's `R__testdata_10_persons.sql` and the Keycloak test users
  (`keycloak/config/development/users.yaml`) already use (*example:* `10000001`–`10000009`, i.e.
  `1000xxxx`), continuing after its highest number. Child rows (notes, orders) hang on those existing
  fictitious persons or on fictitious records of a lower-numbered script. If the repository has no
  fictitious persons yet, ask before inventing a range.
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
