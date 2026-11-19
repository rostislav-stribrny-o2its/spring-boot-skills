package app.lib.security;

import java.io.Serializable;
import java.util.Set;

/**
 * Port to the application's own access data in pattern (a) default — application-roles model
 * (ADR-STACK-00002 point 3; an interface because it crosses the module boundary, ADR-STACK-00001 point 7).
 * Omitted in pattern (b) token-roles variant when the application chooses it in its own ADR.
 * backend-service implements it with one indexed read through JdbcClient (be-jdbc-client-reads):
 * person by principal, entry ban (reference column: suspended), role codes. Tables, SQL and the
 * implementing service: reference.md.
 * Called on every authenticated request: an entry ban applies once it is in the database, regardless
 * of token validity, plus an optional short cache no longer than the access-token lifetime (stack
 * default 5 minutes, ADR-STACK-00002 point 4).
 */
public interface ApplicationAccess {

    /**
     * @param principal      value of the principal claim (example: personal number)
     * @param accountSubject Keycloak "sub"; only for audit, never a key
     */
    AccessDecision decide(String principal, String accountSubject);

    /**
     * ACTIVE, UNPAIRED and NO_ROLE are states of the pattern (a) mechanism itself (access granted; no
     * person matches the principal; a paired person without any role): keep them, the names are the
     * recommended ones. Every further constant is a reason of an entry ban, the application's own
     * decision (ADR-STACK-00002 point 3); SUSPENDED is an EXAMPLE of such a reason — replace or extend
     * it with the application's. NoAccessException reports the constant name as "reason"
     * (UPPER_SNAKE_CASE).
     */
    enum AccessState { ACTIVE, UNPAIRED, NO_ROLE, SUSPENDED }

    /** personId is null when unpaired; roles are role codes as stored in the database. */
    record AccessDecision(AccessState state, Long personId, Set<String> roles) implements Serializable {

        public AccessDecision {
            roles = Set.copyOf(roles);
            if (state == AccessState.ACTIVE && (personId == null || roles.isEmpty())) {
                throw new IllegalArgumentException("ACTIVE needs a person and at least one role");
            }
        }

        /** No person matches the principal (state UNPAIRED of the mechanism). */
        public static AccessDecision unpaired() {
            return new AccessDecision(AccessState.UNPAIRED, null, Set.of());
        }
    }
}
