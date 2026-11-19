package app.lib.web.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one global error handler of backend-service (lib-web, ADR-STACK-00004 point 9). Not yet verified
 * by a build in this repository.
 *
 * - every error body is application/problem+json with type /problems/<code>;
 * - 422 invalid input with "errors" [{field, code, message}]; 400 unreadable body / wrong parameter type;
 * - 403 /problems/no-access (+reason, NoAccessException of lib-security) or /problems/access-denied;
 * - 409 business rule (its own code) or /problems/stale-version (no retry, ADR-STACK-00005 point 14);
 * - texts never repeat submitted values; the driver message of an integrity violation is never
 *   returned or logged (it can carry row values), only the constraint name is logged;
 * - 401 is not produced here: the security chain answers it with an empty body.
 * The correlation ID header is set by CorrelationIdFilter (be-correlation-logging) on every response.
 */
@Slf4j
@RestControllerAdvice
public class GlobalProblemHandler extends ResponseEntityExceptionHandler {

    static final String TYPE_PREFIX = "/problems/";
    static final HttpStatusCode UNPROCESSABLE = HttpStatusCode.valueOf(422);

    public record FieldProblem(String field, String code, String message) {
    }

    @ExceptionHandler(ProblemException.class)
    ProblemDetail handleProblem(ProblemException ex) {
        ProblemDetail problem = problem(ex.status(), ex.code(), ex.getMessage());
        ex.properties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ProblemDetail handleStaleVersion(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "stale-version", "The record was changed meanwhile; reload it");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Integrity violation constraint={}", constraintName(ex));
        return problem(HttpStatus.CONFLICT, "integrity-violation", "The change violates a data rule");
    }

    /** @PreAuthorize denials happen inside the dispatcher; without this the catch-all returns 500. */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "access-denied", "Missing permission for this operation");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception method={} path={}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected error");
    }

    /** Body validation (@Valid on the generated controller's parameters). */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> new FieldProblem(e.getField(), code(e.getCode()), safe(e.getDefaultMessage())))
            .toList();
        return invalid(errors, headers);
    }

    /** Parameter validation (@Min, @Max, ... on path and query parameters). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = ex.getParameterValidationResults().stream()
            .flatMap(r -> r.getResolvableErrors().stream().map(e -> new FieldProblem(
                r.getMethodParameter().getParameterName(),
                code(e.getCodes() == null || e.getCodes().length == 0 ? null : e.getCodes()[e.getCodes().length - 1]),
                safe(e.getDefaultMessage()))))
            .toList();
        return invalid(errors, headers);
    }

    /** Framework errors (400 unreadable body / type mismatch, 404, 405, ...): /problems/<status> and a generic text. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getType() == null || "about:blank".equals(problem.getType().toString()))) {
            HttpStatus known = HttpStatus.resolve(status.value());
            String phrase = known == null ? "Error" : known.getReasonPhrase();
            problem.setType(URI.create(TYPE_PREFIX + phrase.toLowerCase(Locale.ROOT).replace(' ', '-')));
            problem.setDetail(phrase);   // framework details can echo submitted values
        }
        return super.createResponseEntity(body, headers, status, request);
    }

    private ResponseEntity<Object> invalid(List<FieldProblem> errors, HttpHeaders headers) {
        ProblemDetail problem = problem(UNPROCESSABLE, "validation", "The request has invalid fields");
        problem.setProperty("errors", errors);
        return ResponseEntity.status(UNPROCESSABLE).headers(headers).body(problem);
    }

    static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_PREFIX + code));
        return problem;
    }

    /** "NotNull" -> "NOT_NULL" (ADR-STACK-00004 point 7: codes in UPPER_SNAKE_CASE). */
    static String code(@Nullable String constraint) {
        return constraint == null ? "INVALID"
            : constraint.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    /** Bean Validation default messages name the rule, not the value; anything else is replaced. */
    static String safe(@Nullable String message) {
        return message == null || message.contains("'") || message.contains("\"") ? "Invalid value" : message;
    }

    static String constraintName(DataIntegrityViolationException ex) {
        return ex.getMostSpecificCause() instanceof PSQLException psql && psql.getServerErrorMessage() != null
            ? String.valueOf(psql.getServerErrorMessage().getConstraint())
            : "unknown";
    }
}
