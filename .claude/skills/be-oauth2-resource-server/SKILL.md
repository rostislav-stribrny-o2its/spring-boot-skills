---
name: be-oauth2-resource-server
description: >
  Java/Spring backend: use when securing the API with Keycloak as a stateless OAuth2 resource
  server — JWT validation (issuer, JWKS, RS256, audience, azp, clock skew), Keycloak roles and
  application roles to authorities, @PreAuthorize with role constants, the /api/users/me status
  endpoint, CORS/CSRF for a bearer API, or the lib-security module.
---

# OAuth2 resource server with Keycloak

Not yet verified by a build in this repository. All Keycloak runtime behaviour below (claims,
issuer, keys) is unverified until the realm changes in "Changes this needs outside the code"
are made and the Keycloak IT of `be-spring-testing-security` passes.

## Principles

- The backend is a **stateless resource server**: it never issues tokens, shows no login form,
  stores no password, keeps no session. Keycloak authenticates; the backend verifies the bearer
  token itself (signature from Keycloak's keys, issuer, validity, audience and the client the token
  was issued to — per ADR-00004 point 2).
- **RS256 only.** The realm signs access tokens with RS256; the backend fetches the public keys
  (JWKS) and needs no shared secret. HS256 tokens are rejected.
- **One converter** turns a verified token into authorities; **one class** (`Roles`) names them.
- Everything that differs per environment comes from environment variables (ADR-00003 point 7).
- Lives in **`lib-security`** (package `app.lib.security`) with ONE entry configuration
  `LibSecurityConfiguration`, which imports `lib-web` (global problem handler, correlation filter);
  only `backend-service` composes it, by explicit `@Import` (`be-maven-reactor`). No
  auto-configuration of our own.

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
| `templates/UsersApiDelegateImpl.java` | `GET /api/users/me` sketch: the API delegate in `backend-service` |
| `templates/application-security.yaml` | configuration fragment |

## Token validation

```yaml
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${OPERETA_OIDC_ISSUER_URI}   # https://<app origin>/auth/realms/<realm>
  jwk-set-uri: ${OPERETA_OIDC_JWKS_URI}    # http://keycloak:8080/auth/realms/<realm>/protocol/openid-connect/certs
  jws-algorithms: RS256
  audiences: ${OPERETA_OIDC_AUDIENCE}      # the API audience, e.g. opereta-api
```

**Issuer vs. JWKS URI.** Keycloak is served behind the frontend origin under `/auth`
(`KC_HTTP_RELATIVE_PATH=/auth`, `KC_HOSTNAME=https://<app origin>/auth`), so the `iss` claim is
`https://<app origin>/auth/realms/<realm>` — the address the **browser** used. Inside Docker Compose
(or K3s, should it ever be introduced — ADR-00003 point 1) the backend often cannot reach that public
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

**Audience and authorized party** (per ADR-00004 point 2):

- `audiences` → `aud` must contain the API audience. Without it, any token the realm minted for any
  client is accepted. Keycloak adds the API to `aud` only with an audience mapper (see below).
- `azp` must equal the frontend client id. Boot adds every `OAuth2TokenValidator<Jwt>` bean to the
  auto-configured decoder; `LibSecurityConfiguration` declares one for `azp`. If a build shows the bean
  is not picked up, declare the `JwtDecoder` yourself (`NimbusJwtDecoder.withJwkSetUri(...)
  .jwsAlgorithm(SignatureAlgorithm.RS256).build()` + `setJwtValidator(new
  DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), new JwtIssuerValidator(issuer),
  audience, azp))`) — then the `audiences`/`jws-algorithms` properties are no longer used.

**Clock skew.** `exp`/`nbf` are checked with Spring's default tolerance of 60 s. Keep it: access tokens
live 5 minutes (ADR-00004 point 7), and clocks are synchronised by infrastructure (ADR-00003 point 10).
Do not raise the tolerance to hide clock drift — fix the clock. A different tolerance needs the own
`JwtDecoder` above with `new JwtTimestampValidator(Duration.ofSeconds(n))`.

**Errors.** Missing, invalid, expired token or a failed validator → `401` with
`WWW-Authenticate: Bearer error="invalid_token"` and an empty body (Spring default; the frontend
expects exactly this).

## Authorities

Keycloak puts realm roles in `realm_access.roles` and client roles in
`resource_access.<client-id>.roles`. The converter reads both (client roles only for
`app.security.role-client-id`) and normalises names (`opereta-user` → `OPERETA_USER`).

Opereta uses the **application-roles model** (per ADR-00004 points 4–6):

| Authority | Granted when | Reaches |
|---|---|---|
| `ROLE_ENTRY` | the token carries the configured entry role (`app.security.entry-role`) | `/api/users/me` only |
| `ROLE_ACTIVE` | entry role **and** the database says: paired, not suspended, at least one role | `/api/**` (URL rule) |
| `ROLE_<CODE>` | each business role from the application database, only when `ACTIVE` | operations via `@PreAuthorize` |

- Other roles in the token are **ignored**: business roles live in the application database, never in
  the token (per ADR-00004 point 4: user roles are kept by Opereta, not by AD). Never grant the same
  decision from both sources.
- **Principal** = claim `personal_number` (`app.security.principal-claim`); per ADR-00004 point 5 the
  personal number identifies the person — not e-mail, username or login. A token without it → `401`.
  `sub` (the technical account id) is passed on only for audit.
- **Every request** asks `ApplicationAccess` (one indexed query: person, suspension, role codes):
  per ADR-00004 point 6 a ban takes effect once it is written to the database, plus an optional cache
  of at most 5 minutes. Start without a cache; add one (Spring Cache, `expireAfterWrite` ≤ 5 min) only
  when measurement shows the load matters.
- **Token-roles variant** (an application without its own role data): map every token role to
  `ROLE_<NAME>` instead of calling `ApplicationAccess`. Not used in Opereta.

## Method security and role constants

```java
@Override
@PreAuthorize(Roles.HAS_PROJECT_MANAGER)        // compile-time constant, defined once in Roles
public ResponseEntity<ProjectDto> updateProject(Long id, UpdateProjectDto body) { … }
```

- `@EnableMethodSecurity` is on `LibSecurityConfiguration`. **Every** public method of an API
  delegate (`…ApiDelegateImpl`, `be-api-contract-first`) carries a `@PreAuthorize` with a concrete
  role (per ADR-00004 point 6: each endpoint requires a concrete role); the ArchUnit rule
  `delegateMethodsArePreAuthorized` checks it (`be-archunit-rules`).
- Expressions are constants in `Roles` (`HAS_…`), never string literals in annotations. A constants
  class is enough — no helper that builds expressions at runtime.
- Business role codes are placeholders until the role analysis fixes them; `ENTRY` and `ACTIVE` are
  reserved names a business role code must not take.
- Data scope (only the user's unit or projects) is not a role check; it belongs in the query
  (`be-jdbc-client-reads`; the mechanism is still open — ADR-00008).

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
  `lib-web` (`be-problem-details-rfc9457`): a person who is unpaired, without role or suspended gets `403`
  `/problems/no-access` with `reason` (`UNPAIRED`, `NO_ROLE`, `SUSPENDED`) on **any** call; everyone
  else `403` `/problems/access-denied`.

## `/api/users/me`

The frontend loads `GET /api/users/me` right after sign-in; the answer decides whether the
application or the "no access" screen is shown. Per ADR-00004 point 6 it is the one status endpoint:
it returns identity, roles and permissions, or a refusal with a reason. ADR-00004 leaves the exact
path to the contract; these skills use `/api/users/me` (no version segment, ADR-00007 point 12), and
`app.security.me-path` must carry the same value.

- Callable with `ROLE_ENTRY` alone; the backend decides from its database.
- `200` → identity, roles, `permissions` (codes the frontend mirrors for display only; the backend
  authorises every endpoint itself). `Cache-Control: no-store`.
- `403` `application/problem+json`, `type` `/problems/no-access`, `reason` `UNPAIRED` · `NO_ROLE` ·
  `SUSPENDED`. The frontend shows the reason and keeps the user signed in.
- An unpaired account is audited with its `sub` (per ADR-00004 point 5: the technical account id is
  stored only for audit).
- Sketch: `templates/UsersApiDelegateImpl.java` — the API delegate makes one call to a query
  service, which throws `NoAccessException` or returns the read model.

## Reading the current user in services

`AppAuthentication.current()` gives the principal (personal number), the person id and the
no-access reason. Services take what they need as parameters where practical; nothing parses
claims except the converter.

## Changes this needs outside the code

Target state of the realm and the environment; check each item in the repository and do what is
missing as a separate task.

1. **Signature algorithm.** In `keycloak/config/realm.yaml` (applies to every environment, overlays in
   `keycloak/config/<environment>/` must not override it) `defaultSignatureAlgorithm: RS256`.
   Tokens issued before a switch fail with `401` until renewed (at most one access-token lifetime).
2. **No other token verification.** Nothing but this backend verifies tokens: no shared HS256 key
   provider in the realm, no database objects that verify tokens, no secret shared for that purpose
   in `.env.example` files or migration placeholders. Where such a setup exists, remove it together
   with its variables; the backend needs only `OPERETA_OIDC_ISSUER_URI`, `OPERETA_OIDC_JWKS_URI`,
   `OPERETA_OIDC_AUDIENCE`, `OPERETA_OIDC_CLIENT_ID` (and optionally `OPERETA_OIDC_ENTRY_ROLE`).
3. **API audience.** A client for the API (no flows enabled) and an audience mapper on the frontend
   client so `aud` contains the API audience.
4. **Entry role.** The entry role as a client role of the API client, granted through the single AD
   entry group of the environment (per ADR-00004 point 4).
5. **Personal number claim.** A user-attribute mapper putting `personal_number` into the access token;
   no name or e-mail in the access token (per ADR-00004 point 2).
6. **Test accounts.** Fictitious accounts with personal numbers matching the fictitious persons of the
   test data (`db-flyway-migrations`), on LOCAL, DEV and TEST, never on PROD (per ADR-00004 point 8).

## Gotchas

- Agent uses `issuer-uri` alone inside containers — startup or first request fails on discovery; add `jwk-set-uri`
- Agent sets `issuer-uri` to the internal Keycloak address — `iss` carries the public origin; the check fails with `401`
- Agent omits `audiences` or the `azp` validator — tokens for other clients are accepted
- Agent keeps HS256 or adds a shared secret — RS256 with JWKS only
- Agent raises clock skew to make tests pass — fix the clock or the test token
- Agent grants business roles from token claims — application database only; the token gives `ENTRY`
- Agent identifies people by `sub`, e-mail or username — `personal_number`
- Agent writes role strings in `@PreAuthorize` — constants from `Roles`
- Agent leaves an operation without `@PreAuthorize` or relies on `authenticated()` — every operation needs a concrete role
- Agent returns a generic `403` for a suspended person — `/problems/no-access` with `reason`
- Agent enables CORS for `localhost` or `*` — same origin, CORS stays off
- Agent adds a profile that switches security off — tests use `jwt()`; per ADR-00004 such a profile may serve only automated tests and must not start on DEV, TEST or PROD
- Agent's global `@ExceptionHandler(Exception.class)` swallows `AccessDeniedException` — map it explicitly to `403`
- Agent uses the non-lambda DSL, `and()`, `antMatchers()` or `WebSecurityConfigurerAdapter` — removed in Security 7
- Agent mocks `JwtDecoder` with `@MockBean` — removed in Boot 4; tests use `jwt()` (`be-spring-testing-security`)

## Provenance

Adapted for this application's Java base (JDK 25, Spring Boot 4, Spring Security 7).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/oauth2-resource-server`, MIT License, © Rrezart Prebreza.
Changes: rewritten for Keycloak (RS256 via JWKS, public issuer vs. internal JWKS URI, `azp` validator),
application roles from the database, role constants, `/api/users/me`, `lib-security` module.
Opereta layer: personal number as principal, entry role, no-access reasons, realm tasks.
