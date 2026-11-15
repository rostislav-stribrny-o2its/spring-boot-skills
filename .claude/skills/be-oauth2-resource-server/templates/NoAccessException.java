package app.lib.security;

import app.lib.security.ApplicationAccess.AccessState;
import app.lib.web.error.ProblemException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * The authenticated person has no access to the application (ADR-00004, ADR-00007 point 9).
 * The global handler of lib-web (be-problem-details-rfc9457) turns every ProblemException into
 * 403 application/problem+json, type /problems/no-access, with "reason" = UNPAIRED | NO_ROLE | SUSPENDED.
 */
public class NoAccessException extends ProblemException {

    private final AccessState reason;

    public NoAccessException(AccessState reason) {
        super(HttpStatus.FORBIDDEN, "no-access", "No access to the application");
        if (reason == AccessState.ACTIVE) {
            throw new IllegalArgumentException("ACTIVE is not a no-access reason");
        }
        this.reason = reason;
    }

    public AccessState reason() {
        return reason;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("reason", reason.name());
    }
}
