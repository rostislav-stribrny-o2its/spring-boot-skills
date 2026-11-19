# Access data, status endpoint (/api/users/me) and realm — reference

Part of `be-oauth2-resource-server`. Shapes below are those of the reference implementation of the
stack template; names marked *example* are that application's values. In a new repository read the
real ones first: `backend/backend-service/src/main/resources/db/migration/` (tables),
`backend/api-model/**/user/` (response), `keycloak/config/realm.yaml` (realm). Where the repository
already has them, the repository wins; where it has nothing, create exactly these shapes.
The person identifier, the reasons of an entry ban and the way the entry role is granted are the
application's own decisions (ADR-STACK-00002 point 3); the values below are examples, and an
application supplement skill (name prefix `app-`) that fixes them overrides this file on that point.

## 1. Tables (backend schema, one `V` migration)

```sql
-- V000NN__create_user_access.sql
CREATE TABLE persons (                                   -- example name; the person table of the application
    id              BIGINT GENERATED ALWAYS AS IDENTITY (MAXVALUE 9007199254740991) PRIMARY KEY,
    personal_number VARCHAR(32) NOT NULL UNIQUE,         -- example principal column (value of app.security.principal-claim)
    account_subject VARCHAR(255) UNIQUE,                 -- Keycloak "sub", bound on first sign-in; audit only, never a key
    email           VARCHAR(255),                        -- display data: the token carries none
    full_name       VARCHAR(255),                        -- display name shown by the frontend
    suspended       BOOLEAN NOT NULL DEFAULT false,      -- entry ban (example reason); takes effect on the next request
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (                                     -- business roles; code = authority after Roles.normalize
    id          SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64) NOT NULL UNIQUE,             -- never ENTRY or ACTIVE (reserved)
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(500)
);

CREATE TABLE permissions (                               -- display codes the frontend mirrors; the backend authorises by role
    id          SMALLINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64) NOT NULL UNIQUE,             -- example: 'users:read'
    description VARCHAR(500)
);

CREATE TABLE role_permissions (
    role_id       SMALLINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id SMALLINT NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE person_roles (
    person_id BIGINT   NOT NULL REFERENCES persons (id) ON DELETE CASCADE,
    role_id   SMALLINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    PRIMARY KEY (person_id, role_id)
);
```

Role and permission rows are reference data: insert them in the same `V` migration with
`ON CONFLICT (code) DO NOTHING`. The role codes come from the application's role analysis; until it
exists, create exactly one role (example: `User`) so that a fictitious person can become `ACTIVE`.
The standard audit columns `created_by`/`updated_by` (`db-design-postgres-tables`) belong on
`persons` too; the reference implementation does not have them yet.

## 2. `ApplicationAccess` implementation (`backend-service`, package `app.backend.user`)

One `@Service` implements the port and also serves `/api/users/me` (reference names:
`UserAccessService`, `UserAccessRepository`, `UserPersonRow`, `UserProfile`, `UserApiMapper`).
The method names below are a generic shape (*example:* `findByPrincipal`); where the repository
already has these classes, the repository's names win (the reference implementation names the
lookup after its principal column).

```java
@Override
@Transactional
public AccessDecision decide(String principal, String accountSubject) {     // read-only apart from binding
    // UNPAIRED and NO_ROLE are states of the mechanism; the entry-ban reasons and where they are
    // checked are the application's (example below: SUSPENDED, checked before NO_ROLE).
    Optional<UserPersonRow> person = repository.findByPrincipal(principal);   // example name; never by sub
    if (person.isEmpty()) return AccessDecision.unpaired();       // audited once, in loadProfile (/api/users/me)
    UserPersonRow p = person.get();
    if (accountSubject != null && !accountSubject.isBlank()) {
        if (p.accountSubject() == null) {
            repository.bindAccountSubject(p.id(), accountSubject);   // first sign-in binds sub (audit only)
        } else if (!p.accountSubject().equals(accountSubject)) {
            log.warn("Token subject differs from the subject bound to person id={}", p.id());   // no deny
        }
    }
    Set<String> roles = repository.findRoleCodes(p.id());
    if (p.suspended()) return new AccessDecision(AccessState.SUSPENDED, p.id(), roles);
    if (roles.isEmpty()) return new AccessDecision(AccessState.NO_ROLE, p.id(), Set.of());
    return new AccessDecision(AccessState.ACTIVE, p.id(), roles);
}
```

SQL of the repository (`JdbcClient`, `be-jdbc-client-reads`):

```sql
SELECT id, personal_number, account_subject, email, full_name, suspended
FROM persons WHERE personal_number = :principal;               -- example principal column; also: WHERE id = :id

SELECT r.code FROM roles r
JOIN person_roles pr ON pr.role_id = r.id
WHERE pr.person_id = :personId;

SELECT DISTINCT p.code FROM permissions p
JOIN role_permissions rp ON rp.permission_id = p.id
JOIN person_roles pr ON pr.role_id = rp.role_id
WHERE pr.person_id = :personId;
```

`loadProfile(AppAuthentication)` (`@Transactional(noRollbackFor = NoAccessException.class)`): if
`auth.noAccessReason()` is present → audit an `UNPAIRED` account (§4) and throw
`NoAccessException(reason)`; otherwise load the person by `auth.personId()`, its role codes and
permission codes (both sorted) and return the profile.

## 3. `GET /api/users/me` — `200` body

API model record (`app.api.main.user.UserResponse`; generated `UserResponseDto`):

| Field | Java (model) | Meaning |
|---|---|---|
| `id` | `Long` (`@Schema(maximum = "9007199254740991")`) | person id |
| `personalNumber` | `String` | principal (example field matching `app.security.principal-claim`) |
| `email` | `String`, nullable | display data from `persons` |
| `fullName` | `String`, nullable | display name from `persons` (never from the token) |
| `paired` | `boolean` | always `true` in a `200` |
| `suspended` | `boolean` | always `false` in a `200` |
| `roles` | `List<String>` | role codes, sorted |
| `permissions` | `List<String>` | permission codes, sorted; display only |

Every field is `required` in the contract; `email` and `fullName` are additionally `nullable: true`
(`be-api-contract-first`, "Required and empty"). Endpoint interface: `UsersEndpoint`,
`@RequestMapping("/api/users")`, `@GetMapping("/me")`, `operationId = "getMe"`, `@Tag(name = "Users")`.

`403` body: `application/problem+json`, `type` `/problems/no-access`, property `reason` =
`UNPAIRED` · `NO_ROLE` (states of the mechanism) or one of the application's entry-ban reasons
(example: `SUSPENDED`).

## 4. Audit of an unpaired account

Written once per `/api/users/me` call of the unpaired caller (`loadProfile`, never in `decide`, which
runs on every request) by `backend-service`'s own audit module (package `app.backend.audit`, table `audit_logs`,
`AuditService.record(...)`), not by a library: `action = 'auth.unpaired'`, `resource_type =
'account'`, `resource_id = <sub>`, status `failed`, `extra` = the reason. No personal data other than
`sub` is stored. If the application has no audit module yet, log the event at `WARN` with the `sub`
only and add the audit row when the module exists.

## 5. Realm (`keycloak/config/realm.yaml`, keycloak-config-cli format)

Applied on every Keycloak start; per-environment overlays in `keycloak/config/<environment>/`.

Checklist (the target state):

1. **Signature algorithm** `defaultSignatureAlgorithm: RS256` in `keycloak/config/realm.yaml`
   (applies to every environment; overlays must not override it). Tokens issued before a switch fail
   with `401` until renewed (at most one access-token lifetime).
2. **No other token verification.** No shared HS256 key provider in the realm, no database objects
   that verify tokens, no secret shared for that purpose in `.env.example` files or migration
   placeholders. Where such a setup exists, remove it together with its variables; the backend needs
   only `<PREFIX>_OIDC_ISSUER_URI`, `_JWK_SET_URI`, `_AUDIENCE`, `_CLIENT_ID` (optionally `_ENTRY_ROLE`).
3. **API audience.** A client for the API (no flows enabled; example: `app-api`) and an audience
   mapper on the frontend client (example: `app-frontend`) so `aud` contains the API audience.
4. **Entry role** (example: `app-user`) as a client role of the API client or a realm role,
   granted to whom the application decides in its own ADR (example: through one directory entry
   group, a separate one per environment, whose name is environment configuration).
5. **Principal claim.** A user-attribute mapper putting the principal claim (example:
   `personal_number`) into the access token; no name or e-mail in the access token.
6. **Test accounts.** Fictitious accounts with principals matching the fictitious persons of the
   test data, on LOCAL, DEV and TEST, never on PROD.

Values are *examples* (realm `app`, API client `app-api`, frontend client `app-frontend`,
entry role `app-user`, claim `personal_number`).

```yaml
realm: app
defaultSignatureAlgorithm: RS256
accessTokenLifespan: 300
roles:
  realm:
  - name: app-user                          # entry role (example); realm role or client role of the API client
    description: Entry role permitting access to /api/users/me
clients:
- clientId: app-api                         # API client = audience; no flows
  bearerOnly: true
  publicClient: false
  protocol: openid-connect
- clientId: app-frontend                     # frontend client = azp
  publicClient: true
  standardFlowEnabled: true
  implicitFlowEnabled: false
  attributes:
    pkce.code.challenge.method: S256
  protocolMappers:
  - name: aud-app-api
    protocol: openid-connect
    protocolMapper: oidc-audience-mapper
    config:
      included.client.audience: app-api
      id.token.claim: 'false'
      access.token.claim: 'true'
  - name: personal-number                                # example mapper for app.security.principal-claim
    protocol: openid-connect
    protocolMapper: oidc-usermodel-attribute-mapper
    config:
      user.attribute: personal_number                    # example user attribute
      claim.name: personal_number                        # example claim matching app.security.principal-claim
      jsonType.label: String
      id.token.claim: 'true'
      access.token.claim: 'true'
```

- The user profile must declare the principal attribute (example: `personal_number`; editable by
  `admin` only).
- Test accounts (`keycloak/config/development/users.yaml`, never in `production/`) carry
  principal attribute (example: `attributes.personal_number`) matching `app.security.principal-claim`
  equal to the fictitious persons of `db/testdata` (`db-flyway-migrations`) and the entry role.
- *Example* — granting the entry role through a directory group: an LDAP/AD group mapper in the realm's user
  federation maps the environment's entry group to the entry role; the group name is environment
  configuration (one group per environment). Until the directory is connected, test accounts get the
  role directly.
