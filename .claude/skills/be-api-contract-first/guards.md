# Contract guards

Part of `be-api-contract-first`. The Maven part runs in `./mvnw -B -ntp verify`; the rest runs in
`backend/scripts/api-guards.sh [--base <git ref>] [--head <git ref>]` after `verify` (CI calls it on
every change of `backend/`, `frontend/` or `specs/`; locally run it from the repository root after a
model change — it needs `pnpm` in `frontend/` and `oasdiff` on the `PATH`).

| Guard | Where | Catches |
|---|---|---|
| Freshness: `specs/` equal a fresh export (in CI also committed as is) | `api-guards.sh` step 1 | spec not regenerated, model and spec drifting apart |
| Frontend types regenerated from the committed specs (only when `frontend/` changed) | `api-guards.sh` step 2 | stale generated TypeScript |
| Lint (Spectral) with the "Java-compatible OpenAPI" profile `backend/api-spec/spectral.yaml` | `api-guards.sh` step 3 | OpenAPI other than 3.0.x, operationId not camelCase or not unique, untyped schemas, arrays without item type, 3.1/3.2-only constructs |
| Compatibility (`oasdiff breaking`) against the base branch | `api-guards.sh` step 4 | breaking change (level `ERR`); passes only with the human-set PR label `api-breaking` (ADR-00007, point 12); which changes need it: [fields.md](fields.md) |
| Round trip: Java from the spec compiles (in `backend-service`), TypeScript compiles (frontend) | `verify`, frontend build | invalid or ambiguous spec |
| ArchUnit over the model (`ModelRulesTest` in `api-model`) | `verify` | ID only `Long`, exact numbers only `BigDecimal`, `double` only measured values, no `float`, lists with item schema, enums as refs |
| ArchUnit over generated classes and the backend (`be-archunit-rules`) | `verify` | `Object`/`Map<String,Object>` properties, `@PreAuthorize` on every delegate method, no own `@RestController`, no `app.api..` in `app.backend..` |
| Locked generator config | review | pinned version, the one config file, templates under Git, validation on |

Spectral has no rule for `maxLength`; length limits are a model rule (`@Size`, [fields.md](fields.md)).
The approved `x-` extension list belongs in `docs/design/`; while it does not exist, no `x-`
extension is approved.
