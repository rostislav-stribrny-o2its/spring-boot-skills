package app.lib.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * app.security.* — values from environment variables (see application-security.yaml).
 * Issuer, JWKS URI, algorithm and audience stay in spring.security.oauth2.resourceserver.jwt.*.
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(
        @NotBlank String clientId,        // expected azp: the frontend client
        @NotBlank String principalClaim,  // claim identifying the person (e.g. personal_number)
        @NotBlank String entryRole,       // Keycloak role that admits a user (e.g. app-user)
        String roleClientId,              // client whose client roles are read; null = realm roles only
        @DefaultValue("/api/users/me") @NotBlank String mePath) {  // status endpoint = the contract's path
}
