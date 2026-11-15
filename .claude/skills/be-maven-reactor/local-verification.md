# Local incremental verification in three stages

Part of `be-maven-reactor`. Never re-run the whole reactor from scratch on every compile or test fix.
`*IT` commands need Docker (Testcontainers); without Docker add `-DskipITs` and leave the ITs to CI.

| Stage & changed area | Command (from `backend/`) | Purpose |
| :--- | :--- | :--- |
| **1. Inner loop — single `*Test` / slice / ArchUnit** | `./mvnw -B test -pl :<module> -am -Dtest=<ClassTest>` | Runs only the touched unit/slice test in seconds. |
| **1. Inner loop — single `*IT`** | `./mvnw -B verify -pl :<service> -am -Dtest=none -Dit.test=<ClassIT>` | Skips Surefire and runs only the target Failsafe `*IT`. |
| **1. Inner loop — fast compile / Lombok / MapStruct** | `./mvnw -B -T 1C -DskipTests -Dspotless.check.skip=true test-compile` | Compiles all main, generated and test sources in parallel. |
| **1. Inner loop — resume failed reactor** | `./mvnw -B verify -rf :<failed-module>` | Resumes from the failed module without repeating upstream modules. |
| **2. Per-item check — shared library (`lib-*`)** | `./mvnw -B test -pl :<lib-module> -amd` | Unit-tests the library and every module depending on it (`-amd`). |
| **2. Per-item check — API contract (`api-model` / `api-spec`)** | `./mvnw -B verify -pl :api-model,:api-spec,:backend-service -am`, then `backend/scripts/api-guards.sh` from the repository root | Exports `specs/` on dynamic ports, checks the generated delegates compile and pass; the guards check freshness, Spectral and `oasdiff` (`be-api-contract-first`, guards.md). |
| **2. Per-item check — `layers.xml` / packaging** | the checks of the skill `be-spring-boot-layers` | Verifies the layered JAR packaging. |
| **3. Final gate (once after all items)** | `./mvnw -q spotless:apply && SAST_ENABLED=true SCA_ENABLED=true ./mvnw -B verify` (+ `backend/scripts/api-guards.sh` when the model changed) | The same as CI: all tests incl. ITs, SpotBugs/FindSecBugs (`sast`), CycloneDX SBOM (`sca`). Without Docker: add `-DskipITs` and say in the pull request that ITs ran only in CI. `-T 1C` only for `test-compile` / `package -DskipTests`, never for a full `verify` with Failsafe `*IT`. |

`SAST_ENABLED=true` / `SCA_ENABLED=true` activate the parent-POM profiles `sast` and `sca`
(equivalent: `-Psast -Psca`); `-Dsast.failOnError=false` reports SAST findings without failing.
`api-guards.sh` needs `pnpm` (frontend dependencies installed) and `oasdiff` on the `PATH`.
