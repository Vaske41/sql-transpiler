package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.IntervalLiteral;
import rs.etf.sqltranslator.ast.NullsOrder;
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
    public Void visitBinaryOp(BinaryOp node) {
        BinaryOperator op = node.op();
        if (op == BinaryOperator.JSON_PATH
                || op == BinaryOperator.JSON_PATH_TEXT
                || op == BinaryOperator.JSON_CONTAINS) {
            throw new IllegalStateException(
                    "rule engine contract: PG-only JSON ops must be rewritten/refused before MySQL print");
        }
        return super.visitBinaryOp(node);
    }

    @Override
    protected void renderIntervalLiteral(IntervalLiteral node) {
        if (node.unit().isEmpty()) {
            throw new IllegalStateException(
                    "rule engine contract: compound INTERVAL must be refused before MySQL print");
        }
        out.token("INTERVAL");
        String raw = node.raw();
        if (raw.matches("-?\\d+(\\.\\d+)?")) {
            out.token(raw);
        } else {
            out.token("'" + raw.replace("\\", "\\\\").replace("'", "''") + "'");
        }
        out.token(node.unit().get().toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    protected void renderDataType(DataType type) {
        if (type.arrayDims() > 0) {
            throw new IllegalStateException(
                    "rule engine contract: array type must not reach the MySQL printer");
        }
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

    @Override
    protected void renderNullsOrder(NullsOrder nulls) {
        throw new IllegalStateException(
                "NULLS ordering must be dropped by DropNullsOrderingRule before printing");
    }

    @Override
    protected void renderAlterColumnType(rs.etf.sqltranslator.ast.AlterColumnType node) {
        out.token("MODIFY COLUMN").token(identifier(node.column()));
        renderDataType(node.type());
        if (node.using().isPresent()) {
            throw new IllegalStateException(
                    "rule engine contract: USING must be dropped before MySQL print");
        }
    }

    /**
     * MySQL has no {@code UPDATE … FROM}. When a FROM clause is present (after
     * {@code RewriteUpdateFromForMysqlRule} qualifies SET LHS), emit the multi-table
     * comma-join form: {@code UPDATE t [AS a], src SET t.c = … WHERE …}.
     */
    @Override
    public Void visitUpdateStatement(rs.etf.sqltranslator.ast.UpdateStatement node) {
        if (node.from().isEmpty()) {
            return super.visitUpdateStatement(node);
        }
        out.token("UPDATE").token(dotted(node.table()));
        node.alias().ifPresent(alias -> out.token("AS").token(identifier(alias)));
        out.raw(",");
        node.from().get().accept(this);
        out.token("SET");
        csv(node.assignments());
        node.where().ifPresent(where -> {
            out.token("WHERE");
            where.accept(this);
        });
        return null;
    }
}
