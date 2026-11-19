---
name: be-api-contract-first
description: >
  Java/Spring backend: use when adding or changing a REST operation or API type - the typed API
  model in api-model, the spec exported to specs/**/openapi/** by api-spec (springdoc), number/ID/
  date/error/list conventions, openapi-generator (spring, delegatePattern) in backend-service, the
  ...ApiDelegateImpl, template customizations and the contract guards.
---

# API Contract-First

The contract is the only meeting point of frontend and backend (ADR-STACK-00003). Nobody - human or
AI - writes the OpenAPI document or the generated code. **You write the typed API model in Java;
the build exports the spec; both sides generate code from the committed spec.**

```
docs/design/backend/<area>/          contract description: operations, schemas, errors, permissions
        | the model is written from it (same PR when behaviour changes)
        v
backend/api-model                    Java records, enums, endpoint interfaces - the source of truth
        | ./mvnw verify: api-spec starts a small app, springdoc serves the groups, the build saves them
        v
specs/<app>/openapi/public/<area>/<module>-service-public.json     committed; the commit is its version
        |                                         |
        v                                         v
backend-service: generated ...Api,               frontend: generated TypeScript (per its template)
...ApiController, ...ApiDelegate, ...Dto in target/ (not committed)
        |
        v
...ApiDelegateImpl (hand-written, module's api package)
```

**The backend implements the generated delegate interfaces, never the model interfaces**
(ADR-STACK-00003): the spec is the contract and both sides read it the same way.

## 1. What you may touch

| Artifact | Who writes it |
|---|---|
| `backend/api-model/**` | you (the only API source) |
| `docs/design/backend/<area>/` contract description | you, in the same PR as a behaviour change |
| `specs/**` | the build only - commit what it produced, never edit |
| `backend-service/target/generated-sources/**` | the generator only - never edit, never commit |
| `backend/openapi-generator.json`, `backend/openapi-templates-customization/**`, `backend/openapi-templates-original/**` | controlled template change only ([template-customizations.md](template-customizations.md)), never as part of a feature change |

An API change follows the "API change" steps (ADR-STACK-00003, point 8): description -> model ->
regenerated spec -> backend (and later frontend) compile again. Agreement between code and
description is confirmed by the reviewer of the change, never by a tool comparing spec and
description. The full file list for adding a field (description, model, spec, migration, domain,
entity, row, mappers, tests) is the "Adding a field" checklist in `be-backend-layers`.

`docs/design/backend/` (areas, approved `x-` extensions) may not exist yet in a new repository.
Then: one area `main`, no `x-` extensions at all, and the contract description goes into the pull
request text until the folder is created.

## 2. Writing the API model (`api-model`, package `app.api..`)

- **Records** for request/response types, **enums** for system enumerations, **endpoint
  interfaces** with Spring MVC mapping annotations (`@RequestMapping`, `@GetMapping`, ...) plus
  swagger annotations (`@Tag`, `@Operation`, `@Schema`, `@ArraySchema`). No logic: interface
  methods are `default` and throw `app.api.NotImplementedException`.
- Every endpoint interface carries `@ApiSpec(area = "main", visibility = PUBLIC, module = "backend")`
  - it decides which spec file the operations land in: `area` = an area name from the list in
  `docs/design/backend/` ("main" is the example used throughout this skill), `visibility` =
  who may call it (ADR-STACK-00003 point 3: `PUBLIC` = public interface, `SERVER` = reachable inside the
  internal infrastructure, `INTERNAL` = within one application only; *example:* an application
  whose only API is the main backend's uses `PUBLIC` alone), `module` = the application without
  `-service`. Templates: `templates/ApiSpec.java` (annotation, exception, example interface).
- `operationId`: readable camelCase verb + noun (`listProjects`, `searchPeople`, `updatePlanRows`);
  it becomes the generated method name. `@Tag` groups operations into one generated interface.
- Names in the contract are English; JSON fields `camelCase`; paths under `/api/`, plural nouns,
  `kebab-case`, nesting at most two levels; codes of enums, reasons and errors `UPPER_SNAKE_CASE`
  (ADR-STACK-00004, point 7). **No version segment in paths** (ADR-STACK-00004, point 12): `/api/users/me`.
- No marker extensions for traceability (`x-des-id` and similar are rejected, ADR-STACK-00003). Any `x-`
  extension only from the approved list in `docs/design/`.

### Types (ADR-STACK-00004, points 1-5, 8)

| Value (by its range in the data model) | Java in the model | OpenAPI | Generated Java |
|---|---|---|---|
| technical ID (DB `bigint` <= 2^53-1) | `Long` | `integer`, `int64`, `maximum: 9007199254740991` | `Long` |
| small integer | `Integer` | `integer`, `int32` | `Integer` |
| exact decimal (money, effort in MD), integer above 2^53-1 | `BigDecimal` (`BigInteger` for the latter) | `number`, `format: decimal` | `BigDecimal` |
| measured value without exactness claim | `Double` | `number`, `double` | `Double` |
| date | `LocalDate` | `string`, `date` (`YYYY-MM-DD`) | `LocalDate` |
| instant | `Instant` | `string`, `date-time` (UTC, `Z`) | `OffsetDateTime` (`Instant` <-> `OffsetDateTime` in `CommonMappings`, `be-mapstruct-dto-layers`) |

- **Numbers are never strings**, IDs are numbers. No `typeMappings` or custom number mappings in the
  generator. `float` nowhere. The month `YYYY-MM` encoding is open (decided by the architecture
  owner in `docs/design/`) - ask, do not guess.
- Required fields are `required`; a field the model allows empty is `nullable: true` and carries
  `null`. An omitted field never stands for `null`. How to write each case in the record
  (`@NotNull`, `@Schema(requiredMode = REQUIRED, nullable = true)`, `@Nullable`), length limits
  (`@Size(max = n)` → `maxLength` → `422`) and which changes need the `api-breaking` label:
  [fields.md](fields.md).
- Every field has a concrete type. A free-form object or untyped map only with a reason written in
  the contract description. Lists always declare their item type.
- System enumerations are enums in the contract (`enumAsRef = true`); administrator-managed code
  lists are resources with `id`, `code`, `name`, not enums. Adding an enum value is compatible;
  removing or renaming is breaking. Classify every value set (*fixed enum* / *managed lookup table*
  / *constrained free text*) and keep its values, and every length or range limit, equal in the DB,
  the `api-model`, the domain and the frontend translations.
- Polymorphism (type hierarchies, unions): open — to be decided in a separate ADR (ADR-STACK-00004,
  section on what it does not decide); do not introduce it without that decision.

### Errors, concurrency, lists (ADR-STACK-00004, points 9-11)

- Errors are `application/problem+json` (RFC 9457), `type` = `/problems/<code>`; `401` has an
  empty body. Every operation declares its error responses in the model. Mechanics:
  `be-problem-details-rfc9457`.
- `422` invalid input with a list of field errors (field, code, text); `400` unreadable body or
  wrong parameter type; `403` in two distinct types: `/problems/no-access` with `reason`
  (`UNPAIRED`, `NO_ROLE`, or an entry-ban reason of the application, *example:* `SUSPENDED`) when the person has no access to the application, and
  `/problems/access-denied` for a missing permission to an operation or record; `404` not found;
  `409` business rule violation **or** stale record version (distinct types). Error texts never
  repeat submitted values. No `423`, no `429`.
- Optimistic locking: the record version travels in request and response bodies (no
  `ETag`/`If-Match`); a stale version answers `409` without retry.
- Lists: page number + page size (default 50, maximum 500); the response carries the page items,
  the exact total for the filter and optional totals computed by the backend. Sort by one or more
  fields; the last sort key is always the ID. Search goes through `POST .../search` (terms never in
  the URL or the log). No cursor pagination. Page numbering (0/1-based) and the answer to a page
  size above 500: open (decided by the architecture owner in `docs/design/`).
- API shape: data-oriented reads, service-oriented writes (domain operations, not row updates);
  screen-shaped or bulk operations instead of item-by-item loading, without one endpoint per
  widget. The backend prepares and reduces data.

## 3. Exporting the spec (`api-spec`)

The spec is generated at build by **starting a small application that contains only the API
model** and reading the documents from springdoc (ADR-STACK-00003, point 2). Templates:
`templates/api-spec-pom.xml`, `templates/SpecExportConfiguration.java`,
`templates/ApiSpecApplication.java` (main class and a stub controller).

- `api-spec` = `api-model` + `spring-boot-starter-webmvc` + springdoc. No database, no libraries,
  no application beans. It holds one stub `@RestController` per endpoint interface (no logic; the
  inherited `default` methods are enough for springdoc) and the main class
  `app.apispec.ApiSpecApplication`.
- **One springdoc group per spec file**: group `<visibility>-<area>-<module>`, filtered by the
  `@ApiSpec` of the declaring interface.
- **Type customizers** (a property customizer and a parameter customizer, incl. collection items)
  write the standard types of the table above: `Long` -> `int64` with the maximum,
  `BigDecimal`/`BigInteger` -> `number`/`decimal`. They add no `x-java-type` or other extensions.
- **Jackson 2 exception:** swagger-core resolves types with Jackson 2, so `api-spec` (and only
  `api-spec`) has Jackson 2 on its classpath. Every other module uses Jackson 3 only
  (`be-maven-reactor`).
- **Determinism** (the CI freshness check depends on it): in `application.yml` set
  `springdoc.api-docs.version: openapi_3_0`, `springdoc.writer-with-order-by-keys: true`,
  `springdoc.writer-with-default-pretty-printer: true`; a global customizer forces
  `openapi: 3.0.3` and fixed `servers`. No timestamps, no build-machine URLs.
- **Build** (Maven, in `api-spec`): `build-helper-maven-plugin:reserve-network-port` in `validate`
  (reserves dynamic ephemeral `api-spec.port` and `api-spec.jmxPort`; never define them in static
  `<properties>` — Maven would resolve them before the plugin runs; version from the Boot parent),
  `spring-boot-maven-plugin:start` in
  `pre-integration-test`, `springdoc-openapi-maven-plugin:generate` in `integration-test` - one
  execution per spec file, `apiDocsUrl` `.../v3/api-docs/<group>`, `outputDir`
  `${repo.root}/specs/<app>/openapi/<visibility>/<area>`, `outputFileName`
  `<module>-service-<visibility>.json` - and `stop` in `post-integration-test`.
- Consequence: the export runs in `verify` (also with `-DskipITs`), not in `compile`/`package`.
  When a spec file disappears (area removed), delete it in the same PR.
- File naming: the backend's public spec of area `main` is
  `specs/<app>/openapi/public/main/backend-service-public.json`.
- Switching an existing repository from an earlier export or generator mode:
  [template-customizations.md](template-customizations.md), "Switching".

Verified in the stack's reference implementation (springdoc 3 with the forced `3.0.3`, start/stop
with Spring Boot 4.1, dynamic ports).

## 4. Generating the backend code (`backend-service`)

openapi-generator `spring` (ADR-STACK-00001, point 5) with the **delegate pattern** - the generator mode
this skill set uses; the ADRs fix only the generator and that its configuration and templates
change under control (ADR-STACK-00003, point 6). Configuration: `backend/openapi-generator.json`
(`templates/openapi-generator.json`), version and plugin settings in the parent POM, executions in
`backend-service` (`templates/backend-service-openapi-fragment.xml`).

| Setting | Value | Why |
|---|---|---|
| `generatorName` | `spring` | |
| `delegatePattern` | `true` | hand-written code only in the delegate |
| `interfaceOnly` / `skipDefaultInterface` | `false` / `false` | controller + delegate are generated; with `skipDefaultInterface=true` the delegate pattern stops compiling |
| `useSpringBoot4`, `useJackson3` | `true` | Boot 4, `tools.jackson` |
| `useBeanValidation` | `true` | constraints from the spec |
| `openApiNullable` | `false` | nullability by `nullable: true`, no `JsonNullable` |
| `modelNameSuffix` | `Dto` | `ProjectDto` never clashes with the domain `Project` |
| `hideGenerationTimestamp` | `true` | deterministic output |
| `skipValidateSpec` | `false` | spec validation is never switched off (ADR-STACK-00003) |
| supporting files | `ApiUtil.java` only | full supporting files add a second `@SpringBootApplication` |
| `templateDirectory` | `backend/openapi-templates-customization/JavaSpring` | [template-customizations.md](template-customizations.md) |

- Generated packages: `app.backend.api.generated.<area>` (+ `.model`), sources in
  `backend/backend-service/target/generated-sources/`; never committed (ADR-STACK-00003, point 5).
- Tolerating unknown enum values on input (ADR-STACK-00004, point 6) needs a generator setting: open
  (decided by the architecture owner in `docs/design/`); until then do not add one on your own.

## 5. Implementing the delegate

The generated `...ApiController` calls the `...ApiDelegate`. Its implementation is the **API
adapter**: map `...Dto` <-> domain with a MapStruct mapper, call **one** service method, wrap the
result in `ResponseEntity`. No business logic, no `@Transactional`, no repositories here.

```java
package app.backend.user.api;

import app.backend.api.generated.main.UsersApiDelegate;
import app.backend.api.generated.main.model.UserResponseDto;
import app.backend.user.service.UserAccessService;
import app.lib.security.AppAuthentication;
import app.lib.security.Roles;
// lombok, CacheControl, ResponseEntity, PreAuthorize, Component imports omitted

@Component
@RequiredArgsConstructor
public class UsersApiDelegateImpl implements UsersApiDelegate {

    private final UserAccessService userAccessService;
    private final UserApiMapper mapper;

    @Override
    @PreAuthorize(Roles.HAS_ENTRY)
    public ResponseEntity<UserResponseDto> getMe() {
        var profile = userAccessService.loadProfile(AppAuthentication.current());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(mapper.toDto(profile));
    }
}
```

- The `...Impl` name is allowed because the interface is generated (ADR-STACK-00001, point 7: a service
  interface exists only when a tool generates it, at a module or external-system boundary, or when
  there are several implementations; otherwise a concrete class).
- Every public delegate method carries `@PreAuthorize` with a role constant
  (`be-oauth2-resource-server`); ArchUnit checks it (`be-archunit-rules`).
- Every operation the delegate does not override answers `501 Not Implemented`; if the delegate
  bean is not picked up, **every** operation answers 501. A web test per operation catches both.
- The delegate lives in the module's api package (`app.backend.<module>.api`), so the
  application's component scan finds it. Never write a `@RestController` of your own.

## 6. Guards

Freshness check of `specs/`, Spectral lint, round-trip compilation, ArchUnit over model, generated
classes and backend, `oasdiff` compatibility with the `api-breaking` label, locked generator
config — table and open points in [guards.md](guards.md). All green in `./mvnw -B -ntp verify`.

## 7. Generator template customizations

Controlled changes of the generator templates (`generatedAnnotation.mustache`, `pojo.mustache`
with Lombok constructors on generated DTOs) and the upgrade procedure:
[template-customizations.md](template-customizations.md).

## Gotchas

- Agent edits `specs/**` or generated sources - change the model and rebuild
- Agent makes `backend-service` implement the `api-model` interfaces - implement the generated `...ApiDelegate`
- Agent writes a hand-made `@RestController` for an API operation - operations come only from the spec
- Agent leaves a delegate method without `@PreAuthorize` - every operation needs a concrete role
- Agent types an ID as `String`/`UUID`/`Integer`, or a number as `string` - `Long` with the 2^53-1 maximum; `BigDecimal` for exact values
- Agent adds `typeMappings`, `x-java-type` or `x-des-id` - standard types, approved extensions only
- Agent sets `skipValidateSpec=true`, `skipDefaultInterface=true` or full supporting files - locked config
- Agent puts `@Transactional`, repositories or rules in the delegate - map and call one service method
- Agent adds `/v1/` to paths or uses OpenAPI 3.1 features - no path version, spec is 3.0.3
- Agent answers validation errors with 400 - field errors are 422, unreadable input 400
- Agent puts search terms in query parameters - `POST .../search`
- Agent forgets to commit the regenerated spec - CI freshness check fails
- Agent adds Jackson 2 to a module other than `api-spec` - Jackson 3 everywhere else

## Provenance

Rewritten for this application's Java base (JDK 25, Spring Boot 4, typed API model -> exported
OpenAPI 3.0.3 -> generated code). Generator settings and the delegate pattern derive from
[rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills)
(`skills/spring-boot-4/openapi-first`, `rest-api-conventions`), MIT License, (c) Rrezart Prebreza.
The export by a started model application, the type customizers and the template customizations
follow ADR-STACK-00003.
