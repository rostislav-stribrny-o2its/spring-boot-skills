// Template (lib-web, package app.lib.web.error): exceptions services throw for an error the API
// declares. Four files; split them when copying. Not yet verified by a build in this repository.
// A service knows no HTTP: the exceptions name a kind, only the global handler maps it to a status.

// ---- ProblemKind.java ----
package app.lib.web.error;

/** What went wrong in domain words; only GlobalProblemHandler turns it into an HTTP status. */
public enum ProblemKind {
    NOT_FOUND,
    RULE_VIOLATION,
    NO_ACCESS
}

// ---- ProblemException.java ----
package app.lib.web.error;

import java.util.Map;

/**
 * An error with a stable code: the global handler answers the status of its kind + type /problems/<code>.
 * The detail is a fixed text: never put submitted values, search terms or personal data into it.
 * Thrown by services (not by domain records). Subclasses add properties (e.g. "reason").
 */
public abstract class ProblemException extends RuntimeException {

    private final ProblemKind kind;
    private final String code;

    protected ProblemException(ProblemKind kind, String code, String detail) {
        super(detail);
        this.kind = kind;
        this.code = code;
    }

    public ProblemKind kind() {
        return kind;
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

/** A missing resource, /problems/not-found. "what" is a fixed noun ("Project"), never an ID or a search term. */
public class NotFoundException extends ProblemException {
    public NotFoundException(String what) {
        super(ProblemKind.NOT_FOUND, "not-found", what + " not found");
    }
}

// ---- BusinessRuleException.java ----
package app.lib.web.error;

/**
 * A violated business rule (the handler answers 409, ADR-STACK-00004 point 9). Each rule has its own code,
 * listed in the contract description of its area; the stale-version 409 has the separate type
 * /problems/stale-version.
 */
public class BusinessRuleException extends ProblemException {
    public BusinessRuleException(String code, String detail) {
        super(ProblemKind.RULE_VIOLATION, code, detail);
    }
}
