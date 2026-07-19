package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.DataType;
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

    @Override
    protected int concatPrecedence() {
        return 6;    // CONCAT renders as ADD's '+' token — same level, or minimal
    }                // parens would drop grouping that changes T-SQL semantics

    @Override
    public Void visitBooleanLiteral(BooleanLiteral node) {
        throw new IllegalStateException(
                "rule engine contract: boolean literals are rewritten to 1/0 for T-SQL");
    }

    @Override
    protected void selectModifiers(QuerySpecification spec, Query owner) {
        if (owner != null && topShape(owner)) {
            out.token("TOP").token("(");
            owner.limit().orElseThrow().count().orElseThrow().accept(this);
            out.raw(")");
        }
    }

    @Override
    protected void renderRowLimit(Query query) {
        query.limit().ifPresent(limit -> {
            if (topShape(query)) {
                return;                              // already emitted as TOP
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
    protected void renderDataType(DataType type) {
        if (type.type() == rs.etf.sqltranslator.ast.GenericType.BLOB) {
            out.token("VARBINARY(MAX)");             // carries no args by construction
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
            case BLOB -> throw new AssertionError("handled above");
            case TEXT -> throw new IllegalStateException(
                    "rule engine contract: TEXT must not reach the T-SQL printer");
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
}
