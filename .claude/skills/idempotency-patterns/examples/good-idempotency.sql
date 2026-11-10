-- PostgreSQL. Keep claim, business write and stored response in ONE transaction.
-- Table created by a Flyway V migration; scoped by principal (the base has no tenants).
CREATE TABLE request_result (
    principal_key   text NOT NULL CHECK (length(principal_key) <= 200),  -- issuer + subject / configured claim
    operation       text NOT NULL CHECK (length(operation) <= 100),
    idempotency_key text NOT NULL CHECK (length(idempotency_key) <= 200),
    request_hash    text NOT NULL CHECK (length(request_hash) = 64),     -- hex SHA-256 of canonical request
    response_status integer CHECK (response_status IS NULL OR response_status BETWEEN 200 AND 599),
    response_body   text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (principal_key, operation, idempotency_key)
);

-- JdbcClient named-parameter statement, not part of the migration:
-- INSERT INTO request_result (principal_key, operation, idempotency_key, request_hash)
-- VALUES (:principal, :operation, :key, :hash)
-- ON CONFLICT (principal_key, operation, idempotency_key) DO NOTHING
-- RETURNING idempotency_key;
-- A claimed row must be completed with the business result before committing.
-- No returned row: read existing hash/result in a NEW statement, then compare/replay.
