package com.example.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * app.security.* — bound from environment variables (see application.yml in SKILL.md).
 * Register with @ConfigurationPropertiesScan or @EnableConfigurationProperties(SecurityProps.class).
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProps(
        @NotBlank String principalClaim,   // claim that identifies the person, e.g. "sub"
        @Valid @NotNull Cors cors) {

    public record Cors(@NotNull List<String> allowedOrigins) {   // empty = CORS disabled
    }
}
