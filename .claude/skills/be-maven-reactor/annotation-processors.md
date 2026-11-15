# Annotation processors and Lombok

Part of `be-maven-reactor`. This file is the one source of the processor setup; `be-lombok` and
`be-mapstruct-dto-layers` refer here. Order **Lombok -> `lombok-mapstruct-binding` -> MapStruct**,
in the parent's `pluginManagement` of `maven-compiler-plugin` (JDK 23+ runs no processor found
implicitly):

```xml
<annotationProcessorPaths>
    <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>
    <path><groupId>org.projectlombok</groupId><artifactId>lombok-mapstruct-binding</artifactId><version>${lombok-mapstruct-binding.version}</version></path>
    <path><groupId>org.mapstruct</groupId><artifactId>mapstruct-processor</artifactId><version>${mapstruct.version}</version></path>
</annotationProcessorPaths>
```

- No `-Amapstruct.*` compiler arguments: the MapStruct policies live only in `CentralMapperConfig`
  (`be-mapstruct-dto-layers`), which every mapper must use (ArchUnit).
- `lombok.version` comes from the Boot BOM. `lombok` is a `provided` dependency in the modules that
  use it (not in `api-model`: data carriers there are `record`s); `mapstruct` (annotations) is a
  normal compile dependency in the modules with mappers.
- `backend-service` needs Lombok also because the generated DTOs carry Lombok constructors (the
  one exception in generated code, `be-api-contract-first`).
- `<parameters>true</parameters>` in the same compiler configuration (parameter names for Spring
  and MapStruct).
