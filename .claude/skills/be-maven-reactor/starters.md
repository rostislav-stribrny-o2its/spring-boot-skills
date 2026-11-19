# Spring Boot 4 starters and artifacts

Part of `be-maven-reactor` (section 10). Versions come from the Spring Boot BOM unless the parent
POM pins them.

| Need | Artifact | Where |
|---|---|---|
| REST | `spring-boot-starter-webmvc` (not `-web`) | `backend-service`, `lib-web`, `api-spec`; `scheduler-service` only to serve actuator on the management port |
| validation | `spring-boot-starter-validation` | applications, `api-spec` |
| JSON (Jackson 3) | `spring-boot-starter-jackson` | `lib-core` (so `scheduler-service` has a `JsonMapper` too) |
| resource server | `spring-boot-starter-security-oauth2-resource-server` | `lib-security` |
| JDBC / Spring Data JDBC | `spring-boot-starter-jdbc`, `spring-boot-starter-data-jdbc` (never `-data-jpa`) | `lib-db`, applications |
| migrations | `spring-boot-starter-flyway` + `org.flywaydb:flyway-database-postgresql` | both applications (`db-flyway-migrations`) |
| health, metrics, observations | `spring-boot-starter-actuator` | both applications |
| tracing (trace ID in MDC, `traceparent`) | `spring-boot-starter-opentelemetry` | both applications; `lib-web` needs only `io.micrometer:micrometer-tracing` (API) |
| `@Observed` on chosen methods | `spring-boot-starter-aspectj` (not `-aop`) | where used |
| PostgreSQL driver | `org.postgresql:postgresql` (`runtime`; compile scope in `lib-web` for the constraint name) | applications, `lib-web` |
| ShedLock | `net.javacrumbs.shedlock:shedlock-spring`, `shedlock-provider-jdbc-template` | where jobs run (`be-scheduling-shedlock`) |
| test starters | `spring-boot-starter-<technology>-test` (self-contained) | test scope |
| Testcontainers | `org.testcontainers:testcontainers-postgresql`, `com.github.dasniko:testcontainers-keycloak` (backend) | test scope (`be-testing-conventions`) |

- `spring-boot-starter-classic` / `-test-classic` are transitional only - not in new modules.
- Not yet verified by a build in this repository: the starter names in this table.
