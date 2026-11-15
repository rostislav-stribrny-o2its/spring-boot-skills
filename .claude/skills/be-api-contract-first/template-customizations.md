# Generator template customizations (controlled)

Part of `be-api-contract-first`. Every change against the original templates of the **pinned**
generator version is visible in Git and carried over on each generator upgrade (ADR-00006,
point 6). A change here is a controlled template change in its own pull request, never part of a
feature change.

## Layout

- Customized copies in `backend/openapi-templates-customization/JavaSpring/` (only the changed
  files; the rest falls back to the built-in templates). Each change is wrapped in
  `/* customization - <file> - BEGIN */` ... `/* customization - END */`.
- The originals of the pinned version are kept next to them in Git
  (`backend/openapi-templates-original/JavaSpring/`) so that every customization is a reviewable
  diff. They are unpacked by hand on each upgrade, not by the build:
  `./mvnw dependency:unpack -Dartifact=org.openapitools:openapi-generator:<version>
  -Dmdep.unpack.includes=JavaSpring/** -DoutputDirectory=openapi-templates-original` (run in
  `backend/`; `<version>` = `openapi-generator.version` of the parent POM).

## The default set

- **`generatedAnnotation.mustache`**: adds `@SuppressWarnings("unused")` before `@Generated`
  (silences warnings on generated code). Full file: `templates/generatedAnnotation.mustache`.
- **`pojo.mustache`**: generated DTOs get Lombok constructors. Copy the original of the pinned
  version, then:
  1. inside `{{#jackson}}{{#isClassnameSanitized}} ... {{/isClassnameSanitized}}{{/jackson}}`
     (near the top, before `{{>generatedAnnotation}}`) insert
     ```
     /* customization - pojo.mustache - BEGIN */
     @lombok.AllArgsConstructor
     @lombok.NoArgsConstructor
     /* customization - END */
     ```
     If the original of the pinned version has no such section, insert the block directly before
     `{{>generatedAnnotation}}` and say so in the pull request.
  2. delete the generated no-argument constructor and the "Constructor with only required
     parameters" block - they would clash with the Lombok constructors.

  The block applies only to sanitized class names; `modelNameSuffix: Dto` makes every model
  class sanitized. The original `@JsonTypeName` lines stay. These are the only Lombok annotations
  in generated code (`be-lombok`); never `@Data`. Not yet verified by a build in this repository.

## Switching

- **From `interfaceOnly`:** an earlier configuration with `interfaceOnly: true`,
  `skipDefaultInterface: true` and no `modelNameSuffix` is replaced by the settings table of
  `be-api-contract-first` §4 in one controlled change (its own PR, together with the template
  customizations); feature code written against the earlier interfaces moves into delegates in that PR.
- **From an earlier export:** when the `api-spec` export replaces an earlier one (e.g. a
  `be-service-public.json` written by an `exec-maven-plugin` run without a server), delete the old
  file and the old export execution in the same PR; the freshness check does not notice a stale
  file nobody writes any more.

## Upgrade

Unpack the new originals, diff old vs new originals, re-apply each BEGIN/END block, rebuild,
review the diff of generated code.
