package app.lib.security;

import java.util.Locale;

/**
 * Authority names (without the ROLE_ prefix) and the @PreAuthorize expressions built from them.
 * The only place role strings are written. Expressions are compile-time constants so they can be
 * used in annotations: @PreAuthorize(Roles.HAS_APP_ADMIN).
 */
public final class Roles {

    /** Token carries the configured entry role. Reaches only the status endpoint /api/users/me. Reserved name. */
    public static final String ENTRY = "ENTRY";
    /** Paired, not suspended, at least one role. Required on /api/**. Reserved name. */
    public static final String ACTIVE = "ACTIVE";

    // Business role codes as stored in the application database.
    // PLACEHOLDERS until the role analysis fixes the codes.
    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String MANAGER = "MANAGER";
    public static final String DIRECTOR = "DIRECTOR";
    public static final String PROJECT_MANAGER = "PROJECT_MANAGER";
    public static final String APP_ADMIN = "APP_ADMIN";

    public static final String HAS_ENTRY = "hasRole('" + ENTRY + "')";
    public static final String HAS_EMPLOYEE = "hasRole('" + EMPLOYEE + "')";
    public static final String HAS_PROJECT_MANAGER = "hasRole('" + PROJECT_MANAGER + "')";
    public static final String HAS_APP_ADMIN = "hasRole('" + APP_ADMIN + "')";
    public static final String HAS_MANAGER_OR_DIRECTOR =
        "hasAnyRole('" + MANAGER + "','" + DIRECTOR + "')";

    private Roles() {
    }

    /** Keycloak or database role name -> authority name: "opereta-user" -> "OPERETA_USER". */
    public static String normalize(String role) {
        return role.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }
}
