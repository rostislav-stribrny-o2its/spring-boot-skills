// Template (api-spec): the main class of the export app and one stub controller per endpoint
// interface. Two files in package app.apispec; split them when copying.
// Not yet verified by a build in this repository.

// ---- app/apispec/ApiSpecApplication.java ----
package app.apispec;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Started only at build time to export the spec (ADR-00006, point 2). No database, no libraries. */
@SpringBootApplication
public class ApiSpecApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiSpecApplication.class, args);
    }
}

// ---- app/apispec/UsersEndpointStub.java ----
package app.apispec;

import app.api.user.UsersEndpoint;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stub so that Spring MVC registers the mappings of the model interface and springdoc documents
 * them. No logic: the inherited default methods are enough. One stub per endpoint interface.
 */
@RestController
class UsersEndpointStub implements UsersEndpoint {
}
