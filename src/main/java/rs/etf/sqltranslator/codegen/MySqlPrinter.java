package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.StringLiteral;

/**
 * MySQL renderer. Strings escape backslash (MySQL default sql_mode treats it as an
 * escape character) before quote doubling. BOOLEAN prints as TINYINT(1) — the form
 * MySQL itself stores, and the one our own builder folds back to BOOLEAN.
 */
public final class MySqlPrinter extends AbstractSqlPrinter {

    @Override
    protected String quoteIdentifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }

    @Override
    protected String renderStringLiteral(StringLiteral literal) {
        String escaped = literal.value()
                .replace("\\", "\\\\")               // backslash first, always
                .replace("'", "''");
        return "'" + escaped + "'";
    }

    @Override
    protected String concatOperator() {
        throw new IllegalStateException(
                "rule engine contract: CONCAT operator is lowered to CONCAT() for MySQL");
    }

    @Override
    protected void renderDataType(DataType type) {
        if (type.type() == rs.etf.sqltranslator.ast.GenericType.BOOLEAN) {
            out.token("TINYINT(1)");                 // carries no args by construction
            return;
        }
        String name = switch (type.type()) {
            case TINYINT -> "TINYINT";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INT";
            case BIGINT -> "BIGINT";
            case DECIMAL -> "DECIMAL";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case CHAR -> "CHAR";
            case VARCHAR -> "VARCHAR";
            case TEXT -> "TEXT";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP -> "DATETIME";            // avoids epoch range + tz coercion
            case BLOB -> "BLOB";
            case BOOLEAN -> throw new AssertionError("handled above");
            case NVARCHAR -> throw new IllegalStateException(
                    "rule engine contract: NVARCHAR must not reach the MySQL printer");
        };
        out.token(name);
        renderTypeArgs(type);
    }

    @Override
    protected void renderAutoIncrement() {
        out.token("AUTO_INCREMENT");
    }
}
