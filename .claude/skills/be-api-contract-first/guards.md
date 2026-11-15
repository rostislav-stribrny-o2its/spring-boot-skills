# Contract guards

Part of `be-api-contract-first`. All green in `./mvnw -B -ntp verify` and CI.

| Guard | Catches |
|---|---|
| Freshness: after the build `git diff --exit-code -- specs/` | spec not regenerated, model and spec drifting apart |
| Lint (Spectral, profile in `backend/api-spec/`) over the exported spec | constructs the generators turn into `Object`, missing formats, ID without maximum, extensions outside the approved list |
| Round trip: Java from the spec compiles (in `backend-service`), TypeScript compiles (frontend) | invalid or ambiguous spec |
| ArchUnit over generated classes | `Object`/`Map<String,Object>` properties, `decimal` not `BigDecimal`, `int64` not `Long` |
| ArchUnit over the model | ID only `Long`, exact numbers only `BigDecimal`, `double` only measured values, no `float`, lists with item schema, enums as refs |
| ArchUnit over the backend | `@PreAuthorize` on every delegate method, no own `@RestController`, no `app.api..` in `app.backend..` (`be-archunit-rules`) |
| Compatibility (`oasdiff`) against the spec in `main` | breaking change; passes only with the human-set PR label `api-breaking` (ADR-00007, point 12) |
| Locked generator config | pinned version, the one config file, templates under Git, validation on |

The lint rules, their severity and the approved extension list are set in `docs/design/`: open
(decided by the architecture owner, ADR-00007). How the compatibility check tolerates an added enum
value is set in `docs/design/build-ci/` (open, ADR-00007).
