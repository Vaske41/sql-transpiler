package rs.etf.sqltranslator.core;

/**
 * The three SQL dialects this translator supports, giving 6 translation directions.
 */
public enum Dialect {
    TSQL,
    MYSQL,
    POSTGRESQL;

    /**
     * CLI token → enum. Accepts exactly {@code tsql}, {@code mysql}, {@code postgresql}
     * (case-insensitive). No aliases ({@code postgres} is rejected).
     */
    public static Dialect fromCliName(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "Dialect required: one of tsql, mysql, postgresql");
        }
        return switch (token.trim().toLowerCase()) {
            case "tsql" -> TSQL;
            case "mysql" -> MYSQL;
            case "postgresql" -> POSTGRESQL;
            default -> throw new IllegalArgumentException(
                    "Unknown dialect '" + token + "'; expected tsql, mysql, or postgresql");
        };
    }
}
