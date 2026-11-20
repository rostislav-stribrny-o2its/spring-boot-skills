---
name: db-flyway-migrations
description: >
  PostgreSQL/Flyway: use when creating migrations, schema changes, reference data or fictitious
  LOCAL/DEV data, or when configuring how migrations run per environment (a separate step on every
  environment via `backend/scripts/db.sh migrate` / the `db-migrate` image, owner vs. runtime login, search_path, grants). Covers five-digit
  naming, forward-only fixes, CI checks and safe migration patterns for PostgreSQL 18.
---

# Flyway Migrations

Flyway is the only tool that changes the schema (ADR-STACK-00005 point 1). Nothing derives DDL from Java;
the stack has no JPA. DDL style (types, keys, lookup tables) is in `db-design-postgres-tables`; lock
levels of single statements and large backfills in `db-postgres-database-migration`; safe change
patterns, baselining and concurrent branches in [migration-patterns.md](migration-patterns.md).

This skill describes the stack default (ADR-STACK-00005); it applies unless an application ADR says
otherwise. Application values (the person identifier) appear here only as marked examples.

**Step 0 — load the application supplement.** Before applying any value marked *example*, list
`.agents/skills/app-*/SKILL.md` and load every one whose description names this skill. Such a
supplement (an application skill, name prefix `app-`) carries the application's values and
overrides this skill on the same point.

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

- Each application owns its schema and its own migration history (ADR-STACK-00005 point 11). In the
  application's first phase the backend has **one schema** (modules are packages, not schemas —
  ADR-STACK-00005 point 12; its name is set in `docs/design/database/`, `backend` in the examples);
  `scheduler-service`, where the application has one, owns its own schema (ShedLock table).
- Per schema: an **owner role** that only migrates, and a **runtime role** the application connects
  with, which cannot change the schema.
- **The database and the roles are created by the one-time DB admin script**, never by a migration.
  Migrations grant the runtime role its rights (below).
- Order: the migrations of each application's schema, then both applications (ADR-STACK-00005 point 12).
  The separate migration step (`db-migrate` / `backend/scripts/db.sh`) keeps this order across all environments.
- How the reference implementation runs this on LOCAL and in CI: one script/container that migrates
  each application's schema, then (LOCAL only) the test data — `backend/scripts/db.sh migrate
  local|deploy` and the `db-migrate` image (`dockerfiles/Dockerfile.db-migrate`), started before both
  applications. Read those files in the repository before changing the order.

**`search_path` — one rule for every login:** each application's logins see **its own schema**
first, so its own tables are written **unqualified** everywhere (SQL, `@Table`, migrations); a table
of the **other** application's schema is always **qualified** (example: `<schema>.<table>`).

- runtime login: `spring.datasource.hikari.schema: <own schema>` (Hikari sets it on every
  connection; PostgreSQL's default `"$user", public` would find nothing);
- owner login: Flyway `default-schema: <own schema>` (Flyway sets the `search_path` while it
  migrates); the separate migration step uses the same setting;
- an `*IT` connected as the runtime role reads a table of each migration to prove it (CI check 5).

## Migrations per environment

| Environment | When migrations run | Login | Drop schema |
|---|---|---|---|
| LOCAL | separate step before start (`backend/scripts/db.sh migrate local` or `db-migrate`) | owner (`DB_OWNER_USER`) in migrate step | allowed — locally only (via CLI/volume reset) |
| DEV (reference stack and branch stacks) | separate step before start (`db-migrate` container) | owner in migrate step | never |
| TEST | separate step before start (`db-migrate` container) | owner, known **only** to that step | never |
| PROD | separate step before start, **after a backup** | owner, only that step | never |

In **every** environment (`LOCAL`, `DEV`, `TEST`, `PROD`), migrations run in a separate pre-start
step (`db-migrate` container / `backend/scripts/db.sh migrate local|deploy`). The applications always run
with the runtime login (`spring.datasource.*`), `spring.flyway.enabled` stays `false` in application
runtime configuration, and applications never receive `DB_OWNER_USER` or `DB_OWNER_PASSWORD` (ADR-STACK-00005 point 12).

```yaml
spring:
  datasource:                                   # runtime login: cannot change the schema
    url: ${DB_URL}
    username: ${DB_APP_USER}
    password: ${DB_APP_PASSWORD}
    hikari:
      schema: backend                           # search_path of the runtime login = own schema
  flyway:
    enabled: false                              # migrations run only via separate db-migrate step
```

Variable names above are illustrative; take the real ones from `backend/.env.example` and each
application's `application.yaml` (example: runtime logins `DB_API_USER` for
`backend-service` and `DB_SCHEDULER_USER` for `scheduler-service`, owner `DB_OWNER_USER` used only in the migration step).
`scheduler-service` uses its own schema in place of `backend`.

**Separate step in every environment**: a one-shot, non-root migration container (`dockerfiles/Dockerfile.db-migrate`,
official Flyway image pinned by digest, Flyway CLI version = the Boot-managed one) or CLI script
(`backend/scripts/db.sh migrate local|deploy`) with the `db/migration` folders of both applications, the only holder of
the owner password; deployment = backup (PROD), stop, migrate, start, health check. **Dropping the schema is allowed only on LOCAL**,
by recreating the database volume or Flyway `clean` from the command line (never via application config).
Details: [migration-patterns.md](migration-patterns.md), "Environments".

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
`SELECT, INSERT, UPDATE`). Narrower rights are taken away or granted explicitly in the migration that
creates the table:

```sql
-- example: a table that is append-only for the runtime role
REVOKE UPDATE, DELETE ON notifications FROM "${runtime_role}";
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
- **Forward only** (ADR-STACK-00005 point 11): no `U…` undo files. A mistake is fixed by a new migration
  (`V00043__fix_…`), never by editing or reverting the deployed one.
- **A migration that has run in any shared environment is never modified** — not even a comment
  (the checksum changes and every environment fails validation).
  Nor deleted, also when the change that added it is reverted: an undo is a new `V` file. The
  guard `backend/scripts/migration-guard.sh` (CI) fails a branch that deletes or edits a `V` file of
  `main` (RULE-014).
- `R__` only for objects dropped and re-created as a whole (views, functions used by views). Tables,
  columns, constraints and reference data always go into `V` migrations.
- **Module prefix.** A migration creating or renaming objects of a module with a declared prefix follows
  `db-design-postgres-tables` (section "Naming").
- The ShedLock table of an application is an ordinary `V` migration of that application, in its own
  schema (DDL in `be-scheduling-shedlock`).
- **Index pattern.** An index on a NEW table goes in that table's create file. An index on an EXISTING
  table: `DROP INDEX CONCURRENTLY IF EXISTS <new index>` first, then a plain `CREATE INDEX CONCURRENTLY` (never `IF NOT EXISTS`: a failed concurrent build leaves an INVALID index of that name, which the rerun would keep), then `DROP INDEX CONCURRENTLY` of the index it replaces, all in its own `V` file, plus a sibling
  `<same file name>.sql.conf` containing `executeInTransaction=false`. Flyway's PostgreSQL transactional lock is already off (`backend/scripts/db.sh`,
  `dockerfiles/Dockerfile.db-migrate`, both `application.yaml`); nothing more to configure.

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

**A shrink** (cut the stored values, then `ALTER COLUMN … TYPE VARCHAR(<smaller>)`) is a table rewrite
under ACCESS EXCLUSIVE whatever the cut does: a batched cut cannot shorten it, so the cut stays in the
same `V` file, bounded by `lock_timeout`/`statement_timeout`, with control counts and the recovery note
(what `field.py --max` writes); a large table gets a release window, never a batch job.

### Testing a data migration

Test a migration over existing rows with Testcontainers + Flyway (`/be-spring-testing-persistence`):
start the PostgreSQL container of `backend/backend-service/src/test/java/app/backend/TestcontainersConfiguration.java`,
migrate with `Flyway.configure().target("<previous version>")`, insert synthetic rows with `JdbcClient`, migrate to the new
version, assert the control counts and the transformed values. A plain `AbstractIT` already runs the full migration
chain; use the target-version test only when the data before the migration matters.

## Reference data vs. fictitious data

- **Reference data** (lookup tables, codes the logic relies on) is part of the schema: versioned `V`
  migrations with stable explicit IDs, in every environment. Changing it = a new `V` migration.
- **Fictitious static data** (persons and records to work with right after the first start) exists
  **only on LOCAL and on DEV branch stacks** — never on TEST or PROD (ADR-STACK-00005 point 16).

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
  - in the separate migration step: set `DB_MIGRATE_TESTDATA=true` (the `db-migrate` container and
    `backend/scripts/db.sh migrate local` add the `db/testdata` folder; default `false`).
  Applications never run migrations or load test data at startup (`spring.flyway.enabled: false`).
- **Idempotent**: upsert by natural key (`INSERT … ON CONFLICT (<natural key>) DO UPDATE …`; *example:*
  `ON CONFLICT (personal_number)` for persons keyed by a personal number), look
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
  never on PROD, ADR-STACK-00002 point 5; a person is identified by the business identifier the
  application chose in its own decision — *example:* personal number): every fictitious person carries
  the identifier of a fictitious test account; never a real one.
  Use the range the repository's `R__testdata_10_persons.sql` and the Keycloak test users
  (`keycloak/config/development/users.yaml`) already use (*example:* `10000001`–`10000009`, i.e.
  `1000xxxx`), continuing after its highest number. Child rows (notes, orders) hang on those existing
  fictitious persons or on fictitious records of a lower-numbered script. If the repository has no
  fictitious persons yet and no application supplement fixes a range, use a range that cannot
  collide with real identifiers and state it in the PR.
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

- Agent applies an example value without loading the `app-` supplement that names this skill — Step 0 first: list `.agents/skills/app-*/SKILL.md`
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
- Agent uses a real person identifier (example: personal number) in fictitious data — a range that cannot collide with real identifiers, matching the Keycloak test accounts
- Agent puts lookup values anywhere but `V` migrations — reference data ships with the schema
- Agent mixes `CREATE INDEX CONCURRENTLY` with other statements, or omits `transactional-lock: false` — rejected or hangs
- Agent puts business logic into triggers or functions — business logic lives in the services (ADR-STACK-00005 point 3)
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
