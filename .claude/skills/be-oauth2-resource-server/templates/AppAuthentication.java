package app.lib.security;

import app.lib.security.ApplicationAccess.AccessDecision;
import app.lib.security.ApplicationAccess.AccessState;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Optional;

/**
 * Authentication of one request. getName() is the principal: the stable business id of the person
 * (example: personal number), never e-mail or username. Services read it through current();
 * nobody else reads claims.
 */
public final class AppAuthentication extends JwtAuthenticationToken {

    private final AccessDecision access;   // null when the token lacks the entry role

    public AppAuthentication(Jwt jwt, Collection<? extends GrantedAuthority> authorities,
                             String principal, AccessDecision access) {
        super(jwt, authorities, principal);
        this.access = access;
    }

    public static AppAuthentication current() {
        return currentIfPresent().orElseThrow(
            () -> new AuthenticationCredentialsNotFoundException("No authenticated request"));
    }

    public static Optional<AppAuthentication> currentIfPresent() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AppAuthentication app
            ? Optional.of(app) : Optional.empty();
    }

    /** Value of the configured principal claim (example: personal number). */
    public String principal() {
        return getName();
    }

    /** Internal id of the paired person; empty when unpaired. */
    public Optional<Long> personId() {
        return access == null ? Optional.empty() : Optional.ofNullable(access.personId());
    }

    /** Reason reported as /problems/no-access; empty when access is granted or the entry role is missing. */
    public Optional<AccessState> noAccessReason() {
        return access == null || access.state() == AccessState.ACTIVE
            ? Optional.empty() : Optional.of(access.state());
    }
}
