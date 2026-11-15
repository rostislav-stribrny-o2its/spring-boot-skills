package app.lib.security;

import app.lib.security.ApplicationAccess.AccessDecision;
import app.lib.security.ApplicationAccess.AccessState;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The ONE place that turns a verified Keycloak token into authorities (application-roles model:
 * the token only admits via the entry role; business roles come from the application database).
 * Signature, issuer, audience, azp and expiry are already checked by the
 * JwtDecoder when this runs. Not yet verified by a build in this repository.
 */
public final class KeycloakJwtAuthenticationConverter
        implements Converter<Jwt, AppAuthentication> {

    private static final String ROLE_PREFIX = "ROLE_";

    private final AppSecurityProperties properties;
    private final ApplicationAccess applicationAccess;
    private final String entryRole;

    public KeycloakJwtAuthenticationConverter(AppSecurityProperties properties,
                                              ApplicationAccess applicationAccess) {
        this.properties = properties;
        this.applicationAccess = applicationAccess;
        this.entryRole = Roles.normalize(properties.entryRole());
    }

    @Override
    public AppAuthentication convert(Jwt jwt) {
        String principal = jwt.getClaimAsString(properties.principalClaim());   // example: personal_number
        if (principal == null || principal.isBlank()) {
            // OAuth2AuthenticationException -> 401 with WWW-Authenticate: Bearer error="invalid_token"
            throw new InvalidBearerTokenException("Token without the principal claim");
        }

        if (!tokenRoles(jwt).contains(entryRole)) {
            // Keycloak should never issue such a token; nothing is reachable with it (403 everywhere).
            return new AppAuthentication(jwt, List.of(), principal, null);
        }

        AccessDecision decision = applicationAccess.decide(principal, jwt.getSubject());
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(role(Roles.ENTRY));
        if (decision.state() == AccessState.ACTIVE) {
            authorities.add(role(Roles.ACTIVE));
            decision.roles().forEach(code -> authorities.add(role(Roles.normalize(code))));
        }
        return new AppAuthentication(jwt, authorities, principal, decision);
    }

    /** Realm roles plus client roles of the configured client, normalised (example: "app-user" -> "APP_USER"). */
    private Set<String> tokenRoles(Jwt jwt) {
        Set<String> roles = new HashSet<>();
        addRoles(roles, jwt.getClaimAsMap("realm_access"));
        Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
        String client = properties.roleClientId();
        if (resourceAccess != null && client != null && resourceAccess.get(client) instanceof Map<?, ?> clientAccess) {
            addRoles(roles, clientAccess);
        }
        return roles;
    }

    private static void addRoles(Set<String> target, Map<?, ?> access) {
        if (access != null && access.get("roles") instanceof Collection<?> names) {
            names.forEach(name -> target.add(Roles.normalize(String.valueOf(name))));
        }
    }

    private static GrantedAuthority role(String name) {
        return new SimpleGrantedAuthority(ROLE_PREFIX + name);
    }
}
