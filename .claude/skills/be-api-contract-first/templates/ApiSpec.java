// Template (api-model): the marker that decides which spec file an endpoint interface lands in,
// the model's "not implemented" exception and one endpoint interface as an example.
// Three files in package app.api; split them when copying. Not yet verified by a build in this repository.

// ---- app/api/ApiSpec.java ----
package app.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Puts the operations of one endpoint interface into one spec file:
 * specs/<app>/openapi/<visibility>/<area>/<module>-service-<visibility>.json (ADR-00006, point 3).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)   // springdoc reads it at export time
@Target(ElementType.TYPE)
public @interface ApiSpec {

    /** API area: a folder name from the list of areas in docs/design/backend/ (e.g. "main"). */
    String area();

    /** Who may call the interface; phase 1 uses PUBLIC only. */
    Visibility visibility();

    /** Application without its "-service" suffix: "backend". */
    String module();

    enum Visibility { PUBLIC, SERVER, INTERNAL }
}

// ---- app/api/NotImplementedException.java ----
package app.api;

/** Thrown by the default methods of endpoint interfaces; the model has no logic. */
public class NotImplementedException extends RuntimeException {
    public NotImplementedException() {
        super("API model only - implemented in backend-service from the generated code");
    }
}

// ---- app/api/user/UsersEndpoint.java (example) ----
package app.api.user;

import app.api.ApiSpec;
import app.api.ApiSpec.Visibility;
import app.api.NotImplementedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@ApiSpec(area = "main", visibility = Visibility.PUBLIC, module = "backend")
@Tag(name = "Users")                       // one tag = one generated UsersApi / UsersApiDelegate
@RequestMapping("/api/users")             // no version segment (ADR-00007, point 12)
public interface UsersEndpoint {

    @Operation(operationId = "getMe", summary = "Identity, roles and permissions of the caller")
    @GetMapping("/me")
    default User getMe() {                 // record app.api.user.User -> generated UserDto
        throw new NotImplementedException();
    }
}
