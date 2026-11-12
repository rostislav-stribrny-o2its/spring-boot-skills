---
name: api-versioning
description: >
  Use when versioning a contract-first Spring MVC API in Spring Boot 4 / Spring Framework 7:
  where the version lives in the OpenAPI contract, the one central request resolution
  strategy, supported and default versions, deprecation headers, and how generated
  controllers fit the built-in versioning.
---

# API Versioning (Boot 4 / Framework 7, contract-first)

Spring Framework 7 provides API versioning in the mapping layer. Use it instead of hand-rolled
version-sniffing filters or custom `HandlerMapping` implementations.

In the Elysium Java base the API is **contract-first**: the OpenAPI document is generated from
the typed API model and the controllers are generated from it (`delegatePattern=true`). So the
version is first a **property of the contract**, and Framework 7 versioning is the runtime that
resolves, validates and announces it — configured **centrally**, once per application.

## 1. The version lives in the contract

- Choose one strategy per application and record it in an ADR. Default for the base:
  **request header** `API-Version`, declared in the contract as a reusable header parameter of
  every versioned operation. URLs stay stable (`/api/orders/{id}`), so the reverse proxy routes
  `/api` without knowing versions.
- Alternative: **path segment** (`/api/v1/orders/{id}`), when consumers cannot send headers.
  The segment is then part of every path in the contract, and the central configuration uses
  `usePathSegment(<index>)`. How the generated paths must look for that (literal `/v1/` or a
  `{version}` variable) is **not verified** for the base — confirm with a prototype first.
- Never both. The contract's `info.version` is the version of the document, not the request
  version; do not resolve requests from it.

## 2. Configure resolution centrally

```java
@Configuration
class ApiVersioningConfig implements WebMvcConfigurer {

    private final ApiVersionProps props;   // supported versions, default, from configuration

    ApiVersioningConfig(ApiVersionProps props) {
        this.props = props;
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
            .useRequestHeader("API-Version")
            .setDefaultVersion(props.defaultVersion())          // e.g. "1.0"
            .addSupportedVersions(props.supported().toArray(String[]::new));
    }
}
```

- Exactly one source: request header, query parameter, path segment, or media-type parameter.
- Versioning is required unless a default is configured or `setVersionRequired(false)` is used;
  for an internal API with one frontend, a default keeps old clients working.
- Missing or unsupported versions go through the API error contract (Problem Details,
  see `problem-details-rfc9457`), and the error responses are part of the contract.
- Boot properties under `spring.mvc.apiversion.*` can supply the same values; keep the strategy
  in Java configuration when its behavior must be explicit.

## 3. Generated controllers and versions

- A generated mapping without a `version` attribute serves every supported version. That is the
  normal case: **one version of each operation exists**, and compatible changes (new optional
  fields, new operations) do not bump the request version.
- A **breaking change** gets a new version in the contract and a new operation (or a new
  versioned interface module) generated beside the old one; the old one is marked deprecated in
  the contract and removed after its sunset date.
- Whether the code generator can put Framework 7's `version` attribute on generated mappings
  (vendor extension or custom template) is **not verified** for the base. Until a prototype
  confirms it, do not hand-edit generated controllers to add `version`; prefer the path-segment
  strategy for the rare case of two concurrent versions of the same operation.

## 4. Deprecation

Use `StandardApiVersionDeprecationHandler` for `Deprecation`, `Sunset` and `Link` response
headers, registered on the same `ApiVersionConfigurer` (check the registration method in the
Framework 7 reference; not shown above because it is not verified for the base). Mark the same operations as
`deprecated: true` in the contract so generated clients see it too.

## Hand-written controllers (non-generated APIs only)

For an API that is not generated from a contract, declare versions on the mappings:

```java
@RestController
@RequestMapping("/api/orders")
class OrderController {

    @GetMapping(path = "/{id}", version = "1.0")
    OrderV1 getV1(@PathVariable Long id) { ... }

    @GetMapping(path = "/{id}", version = "1.2+")   // 1.2 and newer
    OrderV2 getV2(@PathVariable Long id) { ... }
}
```

Use semantic version strings; a `+` suffix means "this version and newer".

## Gotchas

- Agent hand-edits generated controllers to add `version` — change the contract and regenerate.
- Agent bumps the request version for a compatible change — only breaking changes get a new version.
- Agent hand-rolls a version filter or a custom `HandlerMapping` — use the central Framework 7 configuration.
- Agent writes `version = 1` - the value is a semantic version `String`, such as `"1.0"`.
- Agent uses `useHeader` or `useQueryParameter` - use `useRequestHeader` or `useQueryParam`.
- Agent enables versioning without a default or `setVersionRequired(false)` - un-versioned requests return 400.
- Agent mixes a path segment and a request header - choose one source of truth.
- Agent puts the version only in code, not in the contract — consumers and generated clients must see it.
- Agent invents custom sunset headers - use `StandardApiVersionDeprecationHandler`.

## Provenance

Adapted for the Elysium Java base (JDK 25, Spring Boot 4, contract-first API with generated controllers).
Source: [rrezartprebreza/spring-boot-skills](https://github.com/rrezartprebreza/spring-boot-skills),
`skills/spring-boot-4/api-versioning`, MIT License, © Rrezart Prebreza.
Changes: version as part of the contract, central configuration from properties, rules for generated
controllers and breaking changes, `Long` IDs.
