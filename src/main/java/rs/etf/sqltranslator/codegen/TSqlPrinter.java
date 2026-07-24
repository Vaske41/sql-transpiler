package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.IntervalLiteral;
import rs.etf.sqltranslator.ast.NullsOrder;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.StringLiteral;

/**
 * T-SQL renderer. Row limits take two shapes: {@code TOP (n)} directly after
 * SELECT for count-only limits on plain queries, and {@code OFFSET m ROWS
 * [FETCH NEXT n ROWS ONLY]} after ORDER BY otherwise — the Validate batch
 * guarantees ORDER BY is present in every non-TOP case. BooleanLiteral and TEXT
 * never reach this printer (Phase 4 rewrites/narrows them).
 */
public final class TSqlPrinter extends AbstractSqlPrinter {

    @Override
    protected String quoteIdentifier(String value) {
        return "[" + value.replace("]", "]]") + "]";
    }

    @Override
    protected String renderStringLiteral(StringLiteral literal) {
        String body = "'" + literal.value().replace("'", "''") + "'";
        return literal.national() ? "N" + body : body;
    }

    @Override
    protected String concatOperator() {
        return "+";
    }

    /** SQL Server infers recursion; never emit the {@code RECURSIVE} keyword. */
    @Override
    protected void renderWithKeyword(boolean recursive) {
        out.token("WITH");
    }

    @Override
    protected int concatPrecedence() {
        return 6;    // CONCAT renders as ADD's '+' token — same level, or minimal
    }                // parens would drop grouping that changes T-SQL semantics

    /** SQL Server: {@code CREATE OR ALTER VIEW}. */
    @Override
    protected void renderCreateOrReplaceView() {
        out.token("OR").token("ALTER");
    }

    @Override
    public Void visitJoin(rs.etf.sqltranslator.ast.Join node) {
        if (node.lateral()) {
            if (node.kind() == rs.etf.sqltranslator.ast.JoinKind.CROSS) {
                out.token("CROSS APPLY");
                node.table().accept(this);
                return null;
            }
            if (node.kind() == rs.etf.sqltranslator.ast.JoinKind.LEFT
                    && (node.on().isEmpty() || isTrueLiteral(node.on().get()))) {
                out.token("OUTER APPLY");
                node.table().accept(this);
                return null;
            }
            throw new IllegalStateException(
                    "rule engine contract: non-foldable LATERAL ON must be refused before T-SQL print");
        }
        return super.visitJoin(node);
    }

    private static boolean isTrueLiteral(rs.etf.sqltranslator.ast.Expression expr) {
        if (expr instanceof BooleanLiteral b) {
            return b.value();
        }
        // RewriteBooleanSemanticsRule lowers TRUE → 1 before T-SQL print.
        return expr instanceof rs.etf.sqltranslator.ast.NumericLiteral n
                && "1".equals(n.text());
    }

    @Override
    public Void visitBooleanLiteral(BooleanLiteral node) {
        throw new IllegalStateException(
                "rule engine contract: boolean literals are rewritten to 1/0 for T-SQL");
    }

    @Override
    public Void visitBinaryOp(BinaryOp node) {
        BinaryOperator op = node.op();
        if (op == BinaryOperator.JSON_GET
                || op == BinaryOperator.JSON_GET_TEXT
                || op == BinaryOperator.JSON_PATH
                || op == BinaryOperator.JSON_PATH_TEXT
                || op == BinaryOperator.JSON_CONTAINS) {
            throw new IllegalStateException(
                    "rule engine contract: JSON operators must be rewritten before T-SQL print");
        }
        return super.visitBinaryOp(node);
    }

    @Override
    protected void renderIntervalLiteral(IntervalLiteral node) {
        throw new IllegalStateException(
                "rule engine contract: INTERVAL must be rewritten to DATEADD or refused before T-SQL print");
    }

    @Override
    protected void selectModifiers(QuerySpecification spec, Query owner) {
        if (owner != null && topShape(owner)) {
            out.token("TOP").token("(");
            owner.limit().orElseThrow().count().orElseThrow().accept(this);
            out.raw(")");
            if (owner.limit().orElseThrow().withTies()) {
                out.token("WITH").token("TIES");
            }
        }
    }

    @Override
    protected void renderRowLimit(Query query) {
        query.limit().ifPresent(limit -> {
            if (topShape(query)) {
                return;                              // already emitted as TOP
            }
            if (limit.withTies()) {
                throw new IllegalStateException(
                        "rule engine contract: WITH TIES + OFFSET must be refused before T-SQL print");
            }
            out.token("OFFSET");
            limit.offset().ifPresentOrElse(offset -> offset.accept(this),
                    () -> out.token("0"));
            out.token("ROWS");
            limit.count().ifPresent(count -> {
                out.token("FETCH NEXT");
                count.accept(this);
                out.token("ROWS ONLY");
            });
        });
    }

    /** TOP applies only to a single-spec query with a count-only limit. */
    private static boolean topShape(Query query) {
        return query.limit()
                .filter(limit -> limit.offset().isEmpty() && limit.count().isPresent())
                .isPresent()
                && query.unionArms().isEmpty();
    }

    @Override
    public Void visitFunctionCall(FunctionCall node) {
        if (!node.orderBy().isEmpty()) {
            if (!node.name().equals("STRING_AGG") || node.star()) {
                throw new IllegalStateException(
                        "in-arg ORDER BY must be reshaped or refused before T-SQL print: "
                                + node.name());
            }
            out.token(node.name()).raw("(");
            node.quantifier().ifPresent(q -> out.token(q.name()));
            csv(node.args());
            out.raw(")");
            out.token("WITHIN").token("GROUP").raw("(");
            out.token("ORDER").token("BY");
            csv(node.orderBy());
            out.raw(")");
            node.filter().ifPresent(f -> {
                out.token("FILTER").raw("(");
                out.token("WHERE");
                f.accept(this);
                out.raw(")");
            });
            node.window().ifPresent(w -> {
                out.token("OVER").raw("(");
                w.accept(this);
                out.raw(")");
            });
            return null;
        }
        return super.visitFunctionCall(node);
    }

    @Override
    protected void renderDataType(DataType type) {
        if (type.arrayDims() > 0) {
            throw new IllegalStateException(
                    "rule engine contract: array type must not reach the T-SQL printer");
        }
        if (type.type() == rs.etf.sqltranslator.ast.GenericType.BLOB) {
            out.token("VARBINARY(MAX)");             // carries no args by construction
            return;
        }
        if (type.type() == rs.etf.sqltranslator.ast.GenericType.UUID) {
            out.token("UNIQUEIDENTIFIER");
            return;
        }
        String name = switch (type.type()) {
            case TINYINT -> "TINYINT";
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INT";
            case BIGINT -> "BIGINT";
            case DECIMAL -> "DECIMAL";
            case FLOAT -> "REAL";
            case DOUBLE -> "FLOAT";
            case CHAR -> "CHAR";
            case VARCHAR -> "VARCHAR";
            case NVARCHAR -> "NVARCHAR";
            case BOOLEAN -> "BIT";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP -> "DATETIME2";
            case BLOB, UUID -> throw new AssertionError("handled above");
            case TEXT -> throw new IllegalStateException(
                    "rule engine contract: TEXT must not reach the T-SQL printer");
            case JSON, JSONB -> throw new IllegalStateException(
                    "rule engine contract: JSON/JSONB must be narrowed before T-SQL print");
        };
        out.token(name);
        renderTypeArgs(type);
    }

    @Override
    protected void renderAutoIncrement() {
        out.token("IDENTITY(1,1)");
    }

    @Override
    protected String addColumnClause() {
        return "ADD";                                // T-SQL rejects ADD COLUMN
    }

    @Override
    protected void renderNullsOrder(NullsOrder nulls) {
        throw new IllegalStateException(
                "NULLS ordering must be dropped by DropNullsOrderingRule before printing");
    }

    @Override
    protected void renderAlterColumnType(rs.etf.sqltranslator.ast.AlterColumnType node) {
        out.token("ALTER COLUMN").token(identifier(node.column()));
        renderDataType(node.type());
        if (node.using().isPresent()) {
            throw new IllegalStateException(
                    "rule engine contract: USING must be dropped before T-SQL print");
        }
    }
}
