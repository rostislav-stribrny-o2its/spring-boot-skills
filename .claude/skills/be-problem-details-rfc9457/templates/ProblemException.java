// Template (lib-web, package app.lib.web.error): exceptions services throw for an error the API
// declares. Three files; split them when copying. Not yet verified by a build in this repository.

// ---- ProblemException.java ----
package app.lib.web.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error with a stable code: the global handler answers status + type /problems/<code>.
 * The detail is a fixed text: never put submitted values, search terms or personal data into it.
 * Thrown by services (not by domain records). Subclasses add properties (e.g. "reason").
 */
public abstract class ProblemException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ProblemException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> properties() {
        return Map.of();
    }
}

// ---- NotFoundException.java ----
package app.lib.web.error;

import org.springframework.http.HttpStatus;

/** 404 /problems/not-found. "what" is a fixed noun ("Project"), never an ID or a search term. */
public class NotFoundException extends ProblemException {
    public NotFoundException(String what) {
        super(HttpStatus.NOT_FOUND, "not-found", what + " not found");
    }
}

// ---- BusinessRuleException.java ----
package app.lib.web.error;

import org.springframework.http.HttpStatus;

/**
 * 409 for a violated business rule (ADR-00007 point 9). Each rule has its own code, listed in the
 * contract description of its area; the stale-version 409 has the separate type /problems/stale-version.
 */
public class BusinessRuleException extends ProblemException {
    public BusinessRuleException(String code, String detail) {
        super(HttpStatus.CONFLICT, code, detail);
    }
}
