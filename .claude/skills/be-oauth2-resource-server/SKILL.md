---
name: be-oauth2-resource-server
description: >
  Java/Spring backend: use when securing the API with Keycloak as a stateless OAuth2 resource
  server — JWT validation (issuer, JWKS, RS256, audience, azp, clock skew), Keycloak roles and
  application roles to authorities, @PreAuthorize with role constants, the status endpoint
  (contract-driven, example: /api/users/me), CORS/CSRF for a bearer API, or the lib-security module.
---

# OAuth2 resource server with Keycloak

Verified in the stack's reference implementation (`lib-security`, a Keycloak IT in Testcontainers).
In a new repository it holds once the realm items in "Changes this needs outside the code" exist and
the Keycloak IT of `be-spring-testing-security` passes. Tables, SQL, the status endpoint body (example: `/api/users/me`), the audit row and a realm
example: [reference.md](reference.md).

This skill describes the stack default (ADR-STACK-00002); it applies unless an application ADR says
otherwise. ADR-STACK-00002 point 3 leaves to the application's own decision who receives the entry
role, the choice of role pattern, the person identifier, the reasons of an entry ban and its causes;
those values appear here and in the templates only as marked examples. The states the pattern (a)
mechanism itself needs — active, unpaired, without role — belong to the stack (recommended names
`ACTIVE`, `UNPAIRED`, `NO_ROLE`).

**Step 0 — load the application supplement.** Before applying any value marked *example*, list
`.agents/skills/app-*/SKILL.md` and load every one whose description names this skill. Such a
supplement (an application skill, name prefix `app-`) carries the application's values and
overrides this skill on the same point.

## Principles

- The backend is a **stateless resource server**: it never issues tokens, shows no login form,
  stores no password, keeps no session. Keycloak authenticates; the backend verifies the bearer
  token itself (signature from Keycloak's keys, issuer, validity, audience and the client the token
  was issued to). The access token carries no personal data (name, e-mail); the frontend gets
  display data from the backend.
- **RS256 only.** The realm signs access tokens with RS256; the backend fetches the public keys
  (JWKS) and needs no shared secret. HS256 tokens are rejected.
- **One converter** turns a verified token into authorities; **one class** (`Roles`) names them.
- Everything that differs per environment comes from environment variables; secrets never enter the
  repository. Read the project's values in the repository, never invent them: the variable prefix in
  `backend/.env.example` (example: `APP_OIDC_*`), clients, roles and claims in
  `keycloak/config/**/realm.yaml`, the `app.security.*` keys in `application*.yaml`.
  - Fixed per project, written in `application.yaml`: `principal-claim` is a configuration value
    the application decides in its own ADR (example: `personal_number`; a claim name, equal in
    every environment) and `me-path` matches the API contract (ADR-STACK-00003; reference example:
    `/api/users/me`).
  - From the environment: issuer, JWKS URI, audience, frontend client id, entry role. The shared
    fragment in `lib-security` has no defaults; the application's `application.yaml` may give
    **LOCAL-only** defaults (`localhost` URLs, the project's own client and role names) so a
    developer starts without `.env`. Never a default that points at a shared environment.
- Lives in **`lib-security`** (package `app.lib.security`) with ONE entry configuration
  `LibSecurityConfiguration`, which imports `lib-web` (global problem handler, correlation filter;
  `NoAccessException` extends `lib-web`'s `ProblemException(HttpStatus, String code, String detail)`,
  template in `be-problem-details-rfc9457`). Only `backend-service` composes it, by explicit
  `@Import` (`be-maven-reactor`). No auto-configuration of our own. If `lib-web` does not exist yet,
  create it first (`be-maven-reactor`, `be-problem-details-rfc9457`).

## Dependency (lib-security)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <!-- Boot 4 name; the Boot 3 name spring-boot-starter-oauth2-resource-server is deprecated -->
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
```

## Templates

| File | What |
|---|---|
| `templates/LibSecurityConfiguration.java` | entry `@Configuration`: filter chain, converter, `azp` validator, access-denied handler |
| `templates/KeycloakJwtAuthenticationConverter.java` | token → principal + authorities (Keycloak roles + application roles) |
| `templates/AppAuthentication.java` | the authentication object services read (principal, person id, no-access reason) |
| `templates/ApplicationAccess.java` | port implemented by `backend-service` against its database |
| `templates/NoAccessException.java` | a `ProblemException` of `lib-web`: `403` `/problems/no-access` with `reason` |
| `templates/Roles.java` | authority names and `@PreAuthorize` expressions |
| `templates/AppSecurityProperties.java` | `app.security.*` (incl. `me-path`) |
| `templates/UsersApiDelegateImpl.java` | `GET /api/users/me`: the API delegate in `backend-service` |
| [reference.md](reference.md) | person/role tables, access SQL, `/api/users/me` body, audit row, realm YAML |
| `templates/application-security.yaml` | configuration fragment |

## Token validation

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${APP_OIDC_ISSUER_URI}       # https://<app origin>/auth/realms/<realm>
  jwk-set-uri: ${APP_OIDC_JWK_SET_URI}     # http://keycloak:8080/auth/realms/<realm>/protocol/openid-connect/certs
  jws-algorithms: RS256
  audiences: ${APP_OIDC_AUDIENCE}          # the API audience (example: app-api)
```

The variable names are `<PREFIX>_OIDC_ISSUER_URI`, `<PREFIX>_OIDC_JWK_SET_URI`,
`<PREFIX>_OIDC_AUDIENCE`, `<PREFIX>_OIDC_CLIENT_ID`, `<PREFIX>_OIDC_ENTRY_ROLE` (prefix example:
`APP`). `_JWK_SET_URI` is canonical; an older `_JWKS_URI` is accepted only as a fallback
(`${…_JWK_SET_URI:${…_JWKS_URI}}`) — never introduce it in new files.

**Issuer vs. JWKS URI.** Keycloak is served behind the frontend origin under `/auth`
(`KC_HTTP_RELATIVE_PATH=/auth`, `KC_HOSTNAME=https://<app origin>/auth`), so the `iss` claim is
`https://<app origin>/auth/realms/<realm>` — the address the **browser** used. Inside Docker Compose
(or Kubernetes) the backend often cannot reach that public
address (TLS ends at the gateway, DNS, hairpin routing). Therefore:

- `issuer-uri` = the public issuer, compared **byte for byte** with `iss` (scheme, host, port, `/auth`,
  no trailing slash);
- `jwk-set-uri` = where the backend fetches keys, typically the internal service address
  (`http://keycloak:8080/auth/realms/<realm>/protocol/openid-connect/certs`).

With both set, Spring Boot builds the decoder from `jwk-set-uri`, validates `iss` against `issuer-uri`
and does **no discovery call at startup**; keys are fetched on the first request, cached, and re-fetched
when an unknown `kid` arrives (key rotation needs no restart). With `issuer-uri` alone, Boot discovers
`<issuer>/.well-known/openid-configuration` from inside the container — this fails exactly where the
public address is unreachable.

**Audience and authorized party:**

- `audiences` → `aud` must contain the API audience. Without it, any token the realm minted for any
  client is accepted. Keycloak adds the API to `aud` only with an audience mapper (see below).
- `azp` must equal the frontend client id. Boot adds every `OAuth2TokenValidator<Jwt>` bean to the
  auto-configured decoder; `LibSecurityConfiguration` declares one for `azp`. Proof: the Keycloak IT
  requests a token through a second client that has the same audience mapper (valid signature and
  `aud`, other `azp`) and expects `401` on `/api/users/me`; a unit test calls the validator with a
  foreign and an empty `azp`. If that IT gets `200`, the bean is not picked up — declare the
  `JwtDecoder` yourself (`NimbusJwtDecoder.withJwkSetUri(...)
  .jwsAlgorithm(SignatureAlgorithm.RS256).build()` + `setJwtValidator(new
  DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), new JwtIssuerValidator(issuer),
  audience, azp))`) — then the `audiences`/`jws-algorithms` properties are no longer used.

**Clock skew.** Spring's default 60 s tolerance on `exp`/`nbf` stays (tokens live 5 minutes); fix
drifting clocks, never raise it. Another tolerance would need the own `JwtDecoder` above.

**Errors.** Missing, invalid, expired token or a failed validator → `401` with
`WWW-Authenticate: Bearer error="invalid_token"` and an empty body (Spring default; the frontend
expects exactly this).

## Authorities

Keycloak puts realm roles in `realm_access.roles` and client roles in
`resource_access.<client-id>.roles`. The converter reads both (client roles only for
`app.security.role-client-id`) and normalises names (example: `app-user` → `APP_USER`).
`role-client-id` is always the API audience (`${<PREFIX>_OIDC_AUDIENCE}`): the entry role may be a
client role of the API client, and only that client's roles count. A realm role of the same name
works too (the reference realm uses a realm role).

The application chooses between two supported role patterns in its own ADR (ADR-STACK-00002 point 3):

### (a) Default — application-roles model
The identity provider only admits (one entry role, `app.security.entry-role`, granted to whom the
application decides — example: through one directory entry group per environment); business roles
and the entry ban live in the application's data (reference
table names such as `persons`, `roles`, `user_access` are examples) and are evaluated on **every request**
via `ApplicationAccess`:

| Authority | Granted when | Reaches |
|---|---|---|
| `ROLE_ENTRY` | the token carries the configured entry role (`app.security.entry-role`) | status endpoint (`app.security.me-path`, example: `/api/users/me`) only |
| `ROLE_ACTIVE` | entry role **and** the database says: paired, not suspended, at least one role | `/api/**` (URL rule) |
| `ROLE_<CODE>` | each business role from the application database, only when `ACTIVE` | operations via `@PreAuthorize` |

- Other roles in the token are **ignored**: business roles live in the application database, never in
  the token or in identity-provider groups. Never grant the same decision from both sources.
- **Principal** = the claim that identifies the person (`app.security.principal-claim`; example:
  `personal_number`) — a stable business id the application chooses in its own ADR (ADR-STACK-00002
  point 3; ADR-STACK-00004 point 2 keeps it out of URLs and logs); never e-mail, username or login
  unless the application's ADR says otherwise — they change on renames and mergers. Accounts are
  paired to persons by it; no match → unpaired, no rights. A token without it → `401`. `sub` (the
  technical account id) is passed on only for audit.
- **Every request** asks `ApplicationAccess` (one indexed query: person, entry ban — reference
  column `suspended` —, role codes): an entry ban (its reasons and causes are the application's;
  example: employment ended) takes effect once it is written to the database, regardless of token
  validity, plus an optional short cache no longer than the access-token lifetime (stack default
  5 minutes, ADR-STACK-00002 point 4). Start without a cache; add one (Spring Cache,
  `expireAfterWrite` within that limit) only when measurement shows the load matters.

### (b) Token-roles variant
Only for an application without its own role data — roles come from the token (`realm_access` /
`resource_access.<role-client-id>`) mapped to `ROLE_<NAME>` instead of calling `ApplicationAccess`.
The application chooses the pattern in its own ADR (ADR-STACK-00002 point 3).

## Method security and role constants

```java
@Override
@PreAuthorize(Roles.HAS_APP_ADMIN)              // compile-time constant, defined once in Roles
public ResponseEntity<OrderDto> updateOrder(Long id, UpdateOrderDto body) { … }
```

- `@EnableMethodSecurity` is on `LibSecurityConfiguration`. **Every** public method of an API
  delegate (`…ApiDelegateImpl`, `be-api-contract-first`) carries a `@PreAuthorize` with a concrete
  role (the backend authorises; each endpoint requires a concrete role); the ArchUnit rule
  `delegateMethodsArePreAuthorized` checks it (`be-archunit-rules`).
- Expressions are constants in `Roles` (`HAS_…`), never string literals in annotations. A constants
  class is enough — no helper that builds expressions at runtime.
- `Roles` of a new application holds `ENTRY`, `ACTIVE` and only the business roles its role analysis
  fixed (the codes in `templates/Roles.java` are examples — delete them). Without an analysis, keep
  one role (example: `USER`) so a person can be `ACTIVE`. Where the repository already has `Roles`,
  the repository's `Roles` wins: use its constants and expressions as they are; a new role is a
  change of that class, never a second constant with the same meaning. Constants are normalised codes (`Admin`
  in the database → `ADMIN`); `ENTRY` and `ACTIVE` are reserved names a role code must not take.
- Data scope (only the user's unit or projects) is not a role check; it belongs in the query
  (`be-jdbc-client-reads`; the mechanism is still open — ADR-STACK-00005).

## Filter chain, CORS and CSRF

See `templates/LibSecurityConfiguration.java`:

- `STATELESS` sessions; form login, HTTP Basic, logout and request cache disabled.
- **CSRF disabled**: the API accepts only `Authorization: Bearer`, never a cookie, so a cross-site
  request cannot carry credentials. Re-enable it the day anything authenticates with a cookie.
- **CORS disabled**: one origin per environment, the gateway routes `/auth` → Keycloak, `/api` →
  backend, `/` → frontend; locally the Vite dev server proxies `/auth` and `/api`. Add a CORS mapping
  with exact origins from the environment only if a real cross-origin caller appears.
- Rules: health permitted; `GET` on `app.security.me-path` (`/api/users/me`) → `ENTRY`, declared
  **before** `/api/**` → `ACTIVE`; everything else `denyAll()` (add explicit rules for anything
  new). The `ERROR` dispatch is permitted so error responses are not turned into `403`. The path is
  never a constant in the library; it must equal the contract's path, and an IT ("paired user whose
  token has only the entry role → `200` on `/api/users/me`") fails when they differ.
- Access denied → one `AccessDeniedHandler` that hands the exception to the global handler of
  `lib-web` (`be-problem-details-rfc9457`): a person refused access gets `403` `/problems/no-access`
  with `reason` on **any** call — the mechanism's states unpaired → `UNPAIRED` and without role →
  `NO_ROLE` (recommended names), plus the application's entry-ban reasons (*example:* suspended →
  `SUSPENDED`); everyone else `403` `/problems/access-denied`.

## Status endpoint (contract-driven, example: `/api/users/me`)

The frontend loads the status endpoint right after sign-in; the answer decides whether the
application or the "no access" screen is shown. It is the one status endpoint: it returns identity,
roles and permissions, or a refusal with a reason. The exact path is set by the API contract
(ADR-STACK-00003, not ADR-STACK-00002); these skills use `/api/users/me` as the reference example (no version
segment, ADR-STACK-00004 point 12), and `app.security.me-path` must carry the same value.

- Callable with `ROLE_ENTRY` alone; the backend decides from its database.
- `200` → `id`, `personalNumber` (example principal field per API contract), `email`, `fullName`
  (display name from the person table, never from the token), `paired`, `suspended`, `roles`,
  `permissions` (codes the frontend mirrors for display only; the backend authorises every endpoint
  itself). `Cache-Control: no-store`. Shape and tables: [reference.md](reference.md).
- `403` `application/problem+json`, `type` `/problems/no-access`, `reason` = `UNPAIRED` · `NO_ROLE`
  (states of the mechanism, recommended names) or one of the application's entry-ban reasons
  (*example:* `SUSPENDED`). The frontend shows the reason and keeps the user signed in.
- An unpaired account is audited with its `sub` (stored only for audit, never a key) as a row of the
  backend's audit table (`action` `auth.unpaired`; reference.md §4).
- `templates/UsersApiDelegateImpl.java` — the API delegate makes one call to the access service,
  which throws `NoAccessException` or returns the profile.

## Reading the current user in services

`AppAuthentication.current()` gives the principal (example: personal number), the person id and the
no-access reason. Services take what they need as parameters where practical; nothing parses
claims except the converter.

## Changes this needs outside the code

Target state of the realm and the environment; check each item in the repository and do what is
missing as a separate task. Details, format (keycloak-config-cli YAML) and a complete example:
[reference.md](reference.md) §5.

1. **RS256** as `defaultSignatureAlgorithm` in `keycloak/config/realm.yaml`, never overridden.
2. **No other token verification**: no HS256 key provider, no token-verifying database objects, no
   shared secret; the backend needs only the five `<PREFIX>_OIDC_*` variables above.
3. **API audience**: an API client without flows and an `oidc-audience-mapper` on the frontend client.
4. **Entry role** granted as the application decides in its own ADR (example: through one directory
   entry group per environment).
5. **Principal claim** in the access token through an `oidc-usermodel-attribute-mapper`; no name or
   e-mail in the access token.
6. **Test accounts** matching the fictitious persons (`db-flyway-migrations`); LOCAL, DEV, TEST only.

## Gotchas

- Agent uses `issuer-uri` alone inside containers — startup or first request fails on discovery; add `jwk-set-uri`
- Agent sets `issuer-uri` to the internal Keycloak address — `iss` carries the public origin; the check fails with `401`
- Agent omits `audiences` or the `azp` validator — tokens for other clients are accepted
- Agent keeps HS256 or adds a shared secret — RS256 with JWKS only
- Agent raises clock skew to make tests pass — fix the clock or the test token
- Agent grants business roles from token claims — application database only (pattern a default); token roles are allowed only when the application explicitly selects pattern (b) in its own ADR (ADR-STACK-00002 point 3)
- Agent applies an example value without loading the `app-` supplement that names this skill — Step 0 first: list `.agents/skills/app-*/SKILL.md`
- Agent identifies people by `sub`, e-mail or username — the principal claim (example: `personal_number`), unless the application's ADR says otherwise
- Agent adds a constant to a template `Roles` when the repository already has `Roles` — the repository's `Roles` wins
- Agent caches access decisions longer than the access-token lifetime — at most that lifetime (stack default 5 minutes)
- Agent writes role strings in `@PreAuthorize` — constants from `Roles`
- Agent leaves an operation without `@PreAuthorize` or relies on `authenticated()` — every operation needs a concrete role
- Agent returns a generic `403` for a person refused for a no-access reason (example: suspended) — `/problems/no-access` with `reason`
- Agent enables CORS for `localhost` or `*` — same origin, CORS stays off
- Agent adds a profile that switches security off — tests use `jwt()`; such a profile may serve only automated tests and must not start on DEV, TEST or PROD
- Agent's global `@ExceptionHandler(Exception.class)` swallows `AccessDeniedException` — map it explicitly to `403`
- Agent uses the non-lambda DSL, `and()`, `antMatchers()` or `WebSecurityConfigurerAdapter` — removed in Security 7
- Agent mocks `JwtDecoder` with `@MockBean` — removed in Boot 4; tests use `jwt()` (`be-spring-testing-security`)

## Provenance

Adapted for this application's Java base (JDK 25, Spring Boot 4, Spring Security 7).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/oauth2-resource-server`, MIT License, © Rrezart Prebreza.
Changes: rewritten for Keycloak (RS256 via JWKS, public issuer vs. internal JWKS URI, `azp` validator),
application roles from the database, role constants, `/api/users/me`, `lib-security` module.
Application values (business-id principal, who receives the entry role, entry-ban reasons and
causes, role codes) are marked examples here; an application supplement skill fixes them.
