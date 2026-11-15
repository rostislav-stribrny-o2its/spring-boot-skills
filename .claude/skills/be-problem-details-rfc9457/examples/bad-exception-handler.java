// BAD — returns Map, exposes internals, wrong status codes (compare templates/GlobalProblemHandler.java)

@RestControllerAdvice
public class GlobalExceptionHandler {                      // ignores Spring's framework error handling

    @ExceptionHandler(OrderNotFoundException.class)
    public Map<String, Object> handleNotFound(OrderNotFoundException ex) {
        return Map.of(                                     // returns Map, not ProblemDetail
            "error", ex.getMessage(),                      // message carries the ID the client sent
            "status", 404
        );                                                 // body says 404 but HTTP status remains 200
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<Map<String, String>> handleBusinessRule(BusinessRuleViolationException ex) {
        return ResponseEntity.ok(                          // returns 200 with error body!
            Map.of("error", ex.getMessage()));             // a business rule is 409 with its own /problems/<code>
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Void> retryLater(OptimisticLockingFailureException ex) {
        return ResponseEntity.status(503).build();         // stale version is 409 /problems/stale-version, no retry
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return ResponseEntity.status(500).body(Map.of(
            "error", ex.getMessage(),                      // exposes raw exception message (security risk)
            "stackTrace", Arrays.toString(ex.getStackTrace())  // NEVER expose stack traces
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest()                 // invalid fields are 422 with a list of field errors
            .body("Validation failed: " + ex.getMessage());  // raw string, echoes the rejected values
    }
}
