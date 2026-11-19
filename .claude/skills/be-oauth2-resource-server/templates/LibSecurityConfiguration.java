package app.lib.security;

import app.lib.web.LibWebConfiguration;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Entry configuration of lib-security. backend-service imports it explicitly:
 * {@code @Import(LibSecurityConfiguration.class)}; it brings lib-web (global problem handler,
 * correlation filter) and through it lib-core. Not yet verified by a build in this repository.
 *
 * Configured for pattern (a) default (application-roles via ApplicationAccess, ADR-STACK-00002 point 3);
 * in pattern (b) token-roles, ApplicationAccess is omitted when the application chooses it in its own ADR.
 *
 * Token validation (issuer, JWKS, RS256, audience) is Spring Boot's auto-configured JwtDecoder,
 * driven by spring.security.oauth2.resourceserver.jwt.* (see application-security.yaml);
 * this class adds the azp check, the converter and the authorization rules.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = LibSecurityConfiguration.class)
@Import(LibWebConfiguration.class)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AppSecurityProperties.class)
public class LibSecurityConfiguration {

    /**
     * The status endpoint path comes from app.security.me-path (example: /api/users/me) and must
     * equal the path in the API contract (ADR-STACK-00003); otherwise the /api/** rule catches it and every new
     * user gets 403. The IT "paired user with ENTRY only -> 200 on the status endpoint" guards it.
     */
    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               AppSecurityProperties properties,
                                               KeycloakJwtAuthenticationConverter jwtAuthenticationConverter,
                                               AccessDeniedHandler problemAccessDeniedHandler) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)          // bearer tokens only, no cookies
            .cors(AbstractHttpConfigurer::disable)          // same origin behind the gateway
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()   // keep real error statuses
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.GET, properties.mePath()).hasRole(Roles.ENTRY)   // before /api/**
                .requestMatchers("/api/**").hasRole(Roles.ACTIVE)
                .anyRequest().denyAll())
            .oauth2ResourceServer(rs -> rs
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
            .exceptionHandling(e -> e.accessDeniedHandler(problemAccessDeniedHandler))
            .build();
    }

    @Bean
    KeycloakJwtAuthenticationConverter jwtAuthenticationConverter(AppSecurityProperties properties,
                                                                  ApplicationAccess applicationAccess) {
        return new KeycloakJwtAuthenticationConverter(properties, applicationAccess);
    }

    /**
     * azp = the client the token was issued to; must equal the frontend client. Spring Boot adds
     * OAuth2TokenValidator beans to the auto-configured JwtDecoder next to the issuer,
     * timestamp (60 s skew) and audience validators.
     */
    @Bean
    OAuth2TokenValidator<Jwt> authorizedPartyValidator(AppSecurityProperties properties) {
        return new JwtClaimValidator<String>(IdTokenClaimNames.AZP, properties.clientId()::equals);
    }

    /**
     * One place decides the 403 body: the global problem handler of lib-web (be-problem-details-rfc9457).
     * A person refused access (unpaired or no role, states of the mechanism; or an entry ban for one of
     * the application's reasons, example: suspended) gets /problems/no-access with reason.
     */
    @Bean
    AccessDeniedHandler problemAccessDeniedHandler(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        return (request, response, denied) -> {
            Exception toResolve = AppAuthentication.currentIfPresent()
                .flatMap(AppAuthentication::noAccessReason)
                .<Exception>map(NoAccessException::new)
                .orElse(denied);
            if (resolver.resolveException(request, response, null, toResolve) == null) {
                response.sendError(HttpStatus.FORBIDDEN.value());
            }
        };
    }
}
