package app.lib.security;

import java.io.Serializable;
import java.util.Set;

/**
 * Port to the application's own access data (an interface because it crosses the module
 * boundary, ADR-00002 point 7). backend-service implements it with one indexed read through
 * JdbcClient (be-jdbc-client-reads): person by principal, suspension, role codes.
 * Called on every authenticated request (ADR-00004 point 6: a ban applies once it is in the
 * database, plus an optional cache of at most 5 minutes).
 */
public interface ApplicationAccess {

    /**
     * @param principal      value of the principal claim (Opereta: personal number)
     * @param accountSubject Keycloak "sub"; only for audit, never a key (ADR-00004 point 5)
     */
    AccessDecision decide(String principal, String accountSubject);

    enum AccessState { ACTIVE, UNPAIRED, NO_ROLE, SUSPENDED }

    /** personId is null when unpaired; roles are role codes as stored in the database. */
    record AccessDecision(AccessState state, Long personId, Set<String> roles) implements Serializable {

        public AccessDecision {
            roles = Set.copyOf(roles);
            if (state == AccessState.ACTIVE && (personId == null || roles.isEmpty())) {
                throw new IllegalArgumentException("ACTIVE needs a person and at least one role");
            }
        }

        public static AccessDecision unpaired() {
            return new AccessDecision(AccessState.UNPAIRED, null, Set.of());
        }
    }
}
