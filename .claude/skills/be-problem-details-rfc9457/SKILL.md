---
name: be-problem-details-rfc9457
description: >
  Java/Spring backend: use when mapping exceptions to HTTP error responses — the one global
  problem handler in lib-web, application/problem+json with type /problems/<code>, 422 field
  errors vs 400 unreadable input, 403 no-access vs access-denied, 409 business rule vs stale
  version, safe error texts and testing error responses.
---

# Problem Details - RFC 9457

ADR-STACK-00004 point 9 fixes the error contract of the API; this skill implements it. There is **one**
global handler (`GlobalProblemHandler`) in `lib-web`, which only `backend-service` imports
(`be-maven-reactor`), plus the correlation ID filter (`be-correlation-logging`). Nothing else
builds error bodies.

## The contract (ADR-STACK-00004 point 9)

| Situation | Status | `type` | Body |
|---|---|---|---|
| missing, invalid or expired token | `401` | — | **empty**; reason in `WWW-Authenticate` (security chain, `be-oauth2-resource-server`) |
| unreadable body, wrong parameter type | `400` | `/problems/bad-request` | generic text |
| invalid input (bean validation, field rules) | `422` | `/problems/validation` | `errors`: list of `{field, code, message}` |
| person has no access to the application — on the status endpoint **and** any other call | `403` | `/problems/no-access` | `reason` (`UNPAIRED` · `NO_ROLE` · an entry-ban reason of the application, *example:* `SUSPENDED`) |
| missing permission to an operation or record | `403` | `/problems/access-denied` | generic text |
| not found | `404` | `/problems/not-found` | fixed noun, no ID |
| business rule violated | `409` | `/problems/<rule-code>` | fixed text |
| stale record version (optimistic lock) | `409` | `/problems/stale-version` | no retry (ADR-STACK-00005 point 14) |
| unexpected | `500` | `/problems/internal-error` | generic text; full exception only in the log |

- Every body is `application/problem+json`; every response (errors included) carries the
  correlation ID header — the filter sets it, not the handler. The correlation ID travels in the header only,
  never as a body property.
- An unknown request parameter is ignored, never `400`. No `423`, no `429`.
- Texts **never repeat submitted values** (search terms, IDs, personal data). Framework messages
  can echo input (`Failed to convert value 'abc'`), so the handler replaces them with the status
  phrase.
- Whether a record outside the user's permission answers `403` or `404`: open (decided by the
  architecture owner, ADR-STACK-00004). The spelling of `<code>` in the type (`no-access` vs `NO_ACCESS`),
  the names of the `errors` items and how `409`/`422` mark a row of a bulk write: open (decided by
  the architecture owner in `docs/design/`); the templates use kebab-case types and
  `errors[{field, code, message}]` provisionally.

## Implementation

Templates: `templates/GlobalProblemHandler.java` (package `app.lib.web.error`),
`templates/ProblemException.java` (`ProblemKind`, `ProblemException`, `NotFoundException`,
`BusinessRuleException`). Compare `examples/bad-exception-handler.java`.

```yaml
spring:
  mvc:
    problemdetails:
      enabled: true      # Spring's own framework error handling, extended by the handler
```

- The handler extends `ResponseEntityExceptionHandler`, so Spring keeps producing the framework
  errors (unreadable JSON, type mismatch, 404/405); `createResponseEntity` gives them a
  `/problems/<status>` type and a generic text.
- Validation: `handleMethodArgumentNotValid` (body) and `handleHandlerMethodValidationException`
  (path and query parameters) answer `422` with field errors; the code is the constraint name in
  `UPPER_SNAKE_CASE` (`NotNull` → `NOT_NULL`).
- Services throw a `ProblemException` subclass for every error the contract declares (not found,
  business rule with its code). The exceptions name a `ProblemKind`, never an HTTP status: only the
  handler maps the kind to 404/409/403 and validation to 422/400, and a service imports nothing of
  `org.springframework.http`. Domain records stay free of HTTP types.
- `OptimisticLockingFailureException` → `409 /problems/stale-version`. A deadlock is retried once
  around the transaction (`be-transactional-patterns`); if the retry fails too, it ends as `500`.
- `DataIntegrityViolationException` → `409 /problems/integrity-violation`; the log gets only the
  constraint name — the driver message can contain row values (`Key (…)=(…)`).
- `AccessDeniedException` from `@PreAuthorize` is thrown inside the dispatcher: the handler maps it
  to `403 /problems/access-denied`. Denials in the filter chain reach the same handler through the
  `AccessDeniedHandler` of `lib-security`, which turns a person without access into
  `NoAccessException` (a `ProblemException` with `reason`).
- `401` never reaches the handler: the resource server answers it with an empty body.
- The catch-all `Exception` handler logs once with the stack trace (`be-correlation-logging`) and
  returns a generic `500`.
- Every operation declares its error responses in the API model (`be-api-contract-first`).

Not yet verified by a build in this repository (Spring Framework 7 signatures of the overridden
methods).

## Verification

Through the generated controller and the real handler (`*IT`, `be-testing-conventions`): for each
operation its declared errors; `422` with `errors` for a missing required field and for a bad query
parameter; `400` for malformed JSON and a wrong path-variable type, with no submitted value in the
body; `403` both types; `409` business rule and stale version; `500` without stack trace or internal
message; `Content-Type: application/problem+json` and the correlation header on every error;
`401` with an empty body.

## Gotchas

- Agent answers validation errors with `400` — field errors are `422`; `400` only for unreadable input
- Agent uses an absolute `type` (`https://…/errors/…`) or `about:blank` — `/problems/<code>`
- Agent retries or answers `503` on a stale version — `409 /problems/stale-version`, no retry
- Agent returns one generic `403` for a suspended person — `/problems/no-access` with `reason`
- Agent writes a second `@RestControllerAdvice` or builds error bodies in a delegate — one handler in `lib-web`
- Agent puts the submitted value, an ID or a search term into `detail` — fixed texts only
- Agent logs `e.getMessage()` of an integrity violation — constraint name only
- Agent relies on advice for filter exceptions — the security chain hands them over explicitly
- Agent puts the handler into `lib-core` — `scheduler-service` has no web layer; it lives in `lib-web`
- Agent returns internal exception messages in 500 responses — log privately and return generic detail

## Official sources

- [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)
- [Spring MVC error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)

## Provenance

Adapted for this application's Java base (JDK 25, Spring Boot 4) and ADR-STACK-00004 point 9.
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills)
(its Spring Boot 4 Problem Details skill), MIT License, © Rrezart Prebreza (see `LICENSE.txt`).
Changes: statuses and types per ADR-STACK-00004 (422/400, two 403 types, two 409 types, relative
`/problems/<code>`), handler in `lib-web`, safe texts, integrity violations; example domain
exceptions replaced by `ProblemException`.
