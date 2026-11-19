package app.lib.security;

import app.lib.security.ApplicationAccess.AccessState;
import app.lib.web.error.ProblemException;
import app.lib.web.error.ProblemKind;
import java.util.Map;

/**
 * The authenticated person has no access to the application: account unpaired or no role (states of
 * the mechanism), or an entry ban for one of the application's reasons (example: suspended) —
 * evaluated via ApplicationAccess in pattern (a) default, ADR-STACK-00002 point 3, ADR-STACK-00004 point 9.
 * Requires lib-web with app.lib.web.error.ProblemException(ProblemKind kind, String code, String detail)
 * and its properties() hook (template in be-problem-details-rfc9457).
 * The global handler of lib-web (be-problem-details-rfc9457) turns every ProblemException into
 * 403 application/problem+json, type /problems/no-access, with "reason" = the AccessState name
 * (UNPAIRED | NO_ROLE | the application's entry-ban reasons, example: SUSPENDED).
 */
public class NoAccessException extends ProblemException {

    private final AccessState reason;

    public NoAccessException(AccessState reason) {
        super(ProblemKind.NO_ACCESS, "no-access", "No access to the application");
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
