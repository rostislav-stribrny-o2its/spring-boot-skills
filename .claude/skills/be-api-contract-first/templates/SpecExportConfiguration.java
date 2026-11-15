package app.apispec;

import app.api.ApiSpec;
import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Template (api-spec): spec groups and type customizers of the export app.
 *
 * <p>The customizers make springdoc write the standard types of ADR-00007, which the generators
 * map by default: Long -> integer/int64 with maximum 2^53-1; BigDecimal, BigInteger ->
 * number/decimal. They apply to properties, parameters and collection items alike.
 * swagger-core resolves types with its own (Jackson 2) mapper, hence {@code Json.mapper()}:
 * api-spec is the only module with Jackson 2 on its classpath (brought by swagger-core); every
 * other module uses Jackson 3 only.
 *
 * <p>Not yet verified by a build in this repository (springdoc 3 API, forcing "3.0.3").
 */
@Configuration(proxyBeanMethods = false)
public class SpecExportConfiguration {

    static final BigDecimal ID_MAXIMUM = new BigDecimal("9007199254740991");

    /**
     * One bean per spec file = per (visibility, area, module) used in @ApiSpec. Area "main" is an
     * example; the list of areas is in docs/design/backend/. Group name = URL suffix used by the
     * Maven export execution of the same file.
     */
    @Bean
    GroupedOpenApi publicMainBackend() {
        return group("public", "main", "backend");
    }

    static GroupedOpenApi group(String visibility, String area, String module) {
        return GroupedOpenApi.builder()
                .group(visibility + "-" + area + "-" + module)
                .addOpenApiMethodFilter(method -> {
                    ApiSpec spec = method.getDeclaringClass().getAnnotation(ApiSpec.class);
                    return spec != null
                            && spec.visibility().name().equalsIgnoreCase(visibility)
                            && spec.area().equals(area)
                            && spec.module().equals(module);
                })
                .build();
    }

    @Bean
    PropertyCustomizer standardNumberProperties() {
        return (schema, annotatedType) -> {
            standardize(annotatedType.getType(), schema);
            return schema;
        };
    }

    @Bean
    ParameterCustomizer standardNumberParameters() {
        return (parameter, methodParameter) -> {
            if (parameter != null) {
                standardize(methodParameter.getGenericParameterType(), parameter.getSchema());
            }
            return parameter;
        };
    }

    /** Settings that must be identical on every export (determinism, version 3.0.3). */
    @Bean
    GlobalOpenApiCustomizer fixedDocumentShape() {
        return openApi -> {
            openApi.setOpenapi("3.0.3");
            openApi.setServers(List.of(new Server().url("/"))); // no build-machine URL in the spec
        };
    }

    static void standardize(Type type, Schema<?> schema) {
        if (type == null || schema == null) {
            return;
        }
        JavaType javaType = Json.mapper().constructType(type);
        if (javaType.isContainerType() && javaType.getContentType() != null && schema.getItems() != null) {
            standardize(javaType.getContentType(), schema.getItems());
            return;
        }
        Class<?> raw = javaType.getRawClass();
        if (raw == Long.class || raw == long.class) {
            schema.setType("integer");
            schema.setFormat("int64");
            schema.setMaximum(ID_MAXIMUM);
        } else if (raw == BigDecimal.class || raw == BigInteger.class) {
            schema.setType("number");
            schema.setFormat("decimal");
        }
    }
}
