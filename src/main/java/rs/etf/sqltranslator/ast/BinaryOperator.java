package rs.etf.sqltranslator.ast;

/** Binary operators representable by {@link BinaryOp}. */
public enum BinaryOperator {
    OR, AND, EQ, NEQ, LT, LTE, GT, GTE, ADD, SUB, MUL, DIV, MOD, CONCAT,
    /** PostgreSQL / MySQL {@code ->} — JSON object/array element as JSON. */
    JSON_GET,
    /** PostgreSQL / MySQL {@code ->>} — JSON object/array element as text. */
    JSON_GET_TEXT,
    /** PostgreSQL {@code #>} — JSON path as JSON. */
    JSON_PATH,
    /** PostgreSQL {@code #>>} — JSON path as text. */
    JSON_PATH_TEXT,
    /** PostgreSQL {@code @>} — JSON containment (refused toward MySQL / T-SQL). */
    JSON_CONTAINS,
    /** PostgreSQL {@code ~} — regex match (PG-only lexeme). */
    REGEX_MATCH,
    /** PostgreSQL {@code ~*} — case-insensitive regex match. */
    REGEX_MATCH_I,
    /** PostgreSQL {@code !~} — regex non-match. */
    REGEX_NOT_MATCH,
    /** PostgreSQL {@code !~*} — case-insensitive regex non-match. */
    REGEX_NOT_MATCH_I
}
