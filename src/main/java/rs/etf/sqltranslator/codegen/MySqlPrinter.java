package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.CreateRoutineStatement;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Cte;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.GroupByKind;
import rs.etf.sqltranslator.ast.IndexColumn;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.ast.IntervalLiteral;
import rs.etf.sqltranslator.ast.NullsOrder;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.SortDirection;
import rs.etf.sqltranslator.ast.StringLiteral;

/**
 * MySQL renderer. Strings escape backslash (MySQL default sql_mode treats it as an
 * escape character) before quote doubling. BOOLEAN prints as TINYINT(1) — the form
 * MySQL itself stores, and the one our own builder folds back to BOOLEAN.
 */
public final class MySqlPrinter extends AbstractSqlPrinter {

    @Override
    protected void renderRowLimit(Query query) {
        query.limit().ifPresent(limit -> {
            // MySQL 8.0.13+: LIMIT n WITH TIES (no OFFSET in the same clause).
            if (limit.withTies()) {
                if (limit.offset().isPresent()) {
                    throw new IllegalStateException(
                            "rule engine contract: LIMIT OFFSET WITH TIES must be refused before MySQL print");
                }
                out.token("LIMIT");
                limit.count().ifPresentOrElse(count -> count.accept(this), () -> out.token("1"));
                out.token("WITH").token("TIES");
                return;
            }
            super.renderRowLimit(query);
        });
    }

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
        if (op == BinaryOperator.REGEX_MATCH) {
            operand(node.left(), 4, false);
            out.token("REGEXP");
            operand(node.right(), 4, true);
            return null;
        }
        if (op == BinaryOperator.REGEX_MATCH_I
                || op == BinaryOperator.REGEX_NOT_MATCH
                || op == BinaryOperator.REGEX_NOT_MATCH_I) {
            throw new IllegalStateException(
                    "rule engine contract: regex operators must be rewritten before MySQL print");
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
        Expression value = node.value();
        if (value instanceof NumericLiteral num) {
            out.token(num.text());
        } else if (value instanceof StringLiteral str) {
            String raw = str.value();
            if (raw.matches("-?\\d+(\\.\\d+)?")) {
                out.token(raw);
            } else {
                out.token("'" + raw.replace("\\", "\\\\").replace("'", "''") + "'");
            }
        } else {
            out.raw("(");
            value.accept(this);
            out.raw(")");
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
            case JSON, JSONB -> "JSON";
            case BOOLEAN -> throw new AssertionError("handled above");
            case NVARCHAR -> throw new IllegalStateException(
                    "rule engine contract: NVARCHAR must not reach the MySQL printer");
            case UUID -> throw new IllegalStateException(
                    "rule engine contract: UUID must be narrowed to CHAR(36) before MySQL print");
            case TIMESTAMP_TZ -> throw new IllegalStateException(
                    "rule engine contract: TIMESTAMP_TZ must be narrowed before MySQL print");
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

    @Override
    public Void visitJsonTableRelation(rs.etf.sqltranslator.ast.JsonTableRelation node) {
        out.token("JSON_TABLE").raw("(");
        node.source().accept(this);
        out.raw(",");
        out.token("'" + node.path().replace("'", "''") + "'");
        out.token("COLUMNS").raw("(");
        boolean first = true;
        for (rs.etf.sqltranslator.ast.ColumnDefinition col : node.columns()) {
            if (!first) {
                out.raw(",");
            }
            first = false;
            out.token(identifier(col.name()));
            renderDataType(col.type());
            out.token("PATH").token("'" + jsonTableColumnPath(node.path(), col).replace("'", "''") + "'");
        }
        out.raw(")").raw(")");
        node.alias().ifPresent(alias -> out.token("AS").token(identifier(alias)));
        return null;
    }

    private static String jsonTableColumnPath(String tablePath,
                                              rs.etf.sqltranslator.ast.ColumnDefinition col) {
        if ("$[*]".equals(tablePath)) {
            return "$";
        }
        return "$." + col.name().value();
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
        if (!node.ctes().isEmpty()) {
            renderWithKeyword(node.recursive());
            boolean first = true;
            for (Cte cte : node.ctes()) {
                if (!first) {
                    out.raw(",");
                }
                first = false;
                cte.accept(this);
            }
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

    /** MySQL 8 functional indexes require doubled parentheses around non-column keys. */
    @Override
    public Void visitIndexColumn(IndexColumn node) {
        if (node.key() instanceof ColumnRef) {
            return super.visitIndexColumn(node);
        }
        out.raw("((");
        node.key().accept(this);
        out.raw("))");
        if (node.direction() == SortDirection.DESC) {
            out.token("DESC");
        }
        return null;
    }

    /** MySQL virtual generated columns omit {@code GENERATED ALWAYS}; stored columns add {@code STORED}. */
    @Override
    protected void renderGeneratedColumn(ColumnDefinition node) {
        node.generatedAs().ifPresent(expr -> {
            out.token("AS").raw("(");
            expr.accept(this);
            out.raw(")");
            if (node.stored()) {
                out.token("STORED");
            }
        });
    }

    @Override
    public Void visitColumnRef(ColumnRef node) {
        if (node.name().parts().size() == 1
                && node.name().last().value().startsWith("@")) {
            out.token(node.name().last().value());
            return null;
        }
        return super.visitColumnRef(node);
    }

    @Override
    protected void renderGroupBy(QuerySpecification spec) {
        if (spec.groupByModifier().map(m -> m.kind() == GroupByKind.ROLLUP).orElse(false)) {
            out.token("GROUP BY");
            csv(spec.groupBy());
            out.token("WITH ROLLUP");
            return;
        }
        super.renderGroupBy(spec);
    }

    @Override
    protected void renderRoutineCharacteristics(CreateRoutineStatement node) {
        out.token("DETERMINISTIC").token("READS").token("SQL").token("DATA");
    }

    @Override
    protected void renderRoutineBody(CreateRoutineStatement node) {
        SelectStatement body = (SelectStatement) node.body().get(0);
        out.token("BEGIN").token("RETURN").token("(");
        body.query().accept(this);
        out.raw(");").token("END");
    }
}
