CREATE TABLE t (
    id SERIAL,
    big_id BIGSERIAL,
    payload JSONB,
    meta JSON,
    guid UUID,
    bin BYTEA
);
