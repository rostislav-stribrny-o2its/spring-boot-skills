# Fields in the API model — required, empty, limits, compatibility

Part of `be-api-contract-first`. Rules of ADR-STACK-00004 points 4, 6, 12 written as Java in `api-model`.

## Required and empty

Every field a response always carries is `required`; a field the data model allows empty is
additionally `nullable: true` and carries `null` — it is never omitted (an omitted field never
stands for `null`). In requests, `required` means the client must send it.

| Field kind | Java in the model (record component) | Spec |
|---|---|---|
| request, must be sent, not empty | `@NotNull String name` | in `required` |
| request, may be sent empty | `@Schema(nullable = true) @Nullable String note` | `nullable: true`, not in `required` |
| response, always has a value | `@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name` (primitives: `int`, `boolean`, `long`) | in `required` |
| response, may be empty | `@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String note` | in `required`, `nullable: true` |

- `@NotNull`, `@Size`, `@Positive`, `@Max` are `jakarta.validation.constraints`; `@Nullable` is
  `org.jspecify.annotations.Nullable` (null-safety of the Java code, `be-null-safety`; it writes
  nothing into the spec on its own); `@Schema` is `io.swagger.v3.oas.annotations.media.Schema`.
- Domain data is not written with `PUT`, `PATCH` or `DELETE` (ADR-STACK-00010, point 6): a write is a
  named `POST` command whose schema declares which fields are required, and removing a record is a
  named command too. An omitted field never stands for `null`.
- The stack's reference implementation does not yet write `required`/`nullable` into response
  schemas; the rule above is the ADR's and wins. Check the exported spec, not only the record.

## Length and range limits

- A text limit is `@Size(max = 500)` on the record component. The export writes `maxLength: 500`
  (and `minLength`), the generator puts `@Size(max = 500)` on the `…Dto`, and bean validation in the
  generated controller answers an overlong value with `422` and a field error before any service
  code runs (`be-problem-details-rfc9457`). Never `@Schema(maxLength = …)` alone: it documents the
  limit without enforcing it.
- Numbers: `@Positive`, `@Min`, `@Max`, `@DecimalMax` likewise.
- **One limit, three places:** the DB column (`varchar(500)` or `CHECK (length(col) <= 500)`,
  `db-design-postgres-tables`), the `@Size` in the model and any domain rule carry the same number.
  The API check must be the first to fire: a value that reaches the database too long is a defect
  (it would surface as `500`).

## Example: an optional note of at most 500 characters

```java
// request (create/update)
public record CustomerNoteCreateRequest(
        @NotNull @Schema(maximum = "9007199254740991") Long customerId,
        @Schema(nullable = true) @Nullable @Size(max = 500) String note) {}

// response
public record CustomerResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maximum = "9007199254740991") Long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String note,
        long version) {}
```

## Compatibility guard (`oasdiff`)

The guard compares every exported spec with its version on the base branch and fails only on
breaking findings (level `ERR`); the PR label `api-breaking`, set by a human, lets a breaking change
through.

| Change | Label needed |
|---|---|
| new optional request field, new response field, new operation, new spec file | no |
| new enum value | no (ADR-STACK-00004 point 6: compatible; oasdiff reports it as a warning, not `ERR`) |
| new subtype in a polymorphic family returned in a response | yes (ADR-STACK-00010 point 8, until the compatibility check says otherwise; a family only in a request: no) |
| new required request field, removed or renamed field, operation or enum value, narrower type or limit, changed path | yes |

A breaking change is split where possible: add the new form, switch the frontend, remove the old one
later (ADR-STACK-00004 point 12).
