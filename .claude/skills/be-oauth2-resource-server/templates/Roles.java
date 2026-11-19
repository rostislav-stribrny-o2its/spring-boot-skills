package app.lib.security;

import java.util.Locale;

/**
 * Authority names (without the ROLE_ prefix) and the @PreAuthorize expressions built from them.
 * In pattern (a) default (application-roles model, ADR-STACK-00002 point 3), business roles come from
 * the application database; in pattern (b) (token-roles variant), they map directly from token roles.
 * The only place role strings are written. Expressions are compile-time constants so they can be
 * used in annotations: @PreAuthorize(Roles.HAS_APP_ADMIN).
 */
public final class Roles {

    /** Token carries the configured entry role. Reaches only the status endpoint (example: /api/users/me). Reserved name. */
    public static final String ENTRY = "ENTRY";
    /** Paired, not suspended (no entry ban), at least one role. Required on /api/**. Reserved name. */
    public static final String ACTIVE = "ACTIVE";

    // Business role codes as stored in the application database, normalised (DB "Admin" -> ADMIN).
    // EXAMPLES ONLY, named as in the stack's reference implementation: the role model is the
    // application's own decision. A new application deletes these and writes the roles its role
    // analysis fixed (without an analysis: one role, e.g. USER, so a person can become ACTIVE).
    // Where the repository already has a Roles class, the repository's Roles wins: use its names and
    // expressions, never a second constant with the same meaning.
    public static final String ADMIN = "ADMIN";
    public static final String USER = "USER";
    public static final String APP_ADMIN = "APP_ADMIN";

    public static final String HAS_ENTRY = "hasRole('" + ENTRY + "')";
    public static final String HAS_ACTIVE = "hasRole('" + ACTIVE + "')";
    public static final String HAS_ADMIN = "hasRole('" + ADMIN + "')";
    public static final String HAS_USER = "hasRole('" + USER + "')";
    public static final String HAS_ADMIN_OR_USER = "hasAnyRole('" + ADMIN + "','" + USER + "')";
    public static final String HAS_APP_ADMIN = "hasAnyRole('" + APP_ADMIN + "','" + ADMIN + "')";

    private Roles() {
    }

    /** Keycloak or database role name -> authority name (example: "app-user" -> "APP_USER"). */
    public static String normalize(String role) {
        return role.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }
}
