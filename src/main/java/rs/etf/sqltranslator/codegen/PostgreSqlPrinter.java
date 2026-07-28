package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.StringLiteral;

/**
 * PostgreSQL renderer. Standard-conforming strings: backslash is a literal
 * character, only the single quote doubles. TINYINT and NVARCHAR never reach this
 * printer (Phase 4 narrows them) — reaching them is a contract violation.
 */
public final class PostgreSqlPrinter extends AbstractSqlPrinter {

    @Override
    public Void visitFunctionCall(FunctionCall node) {
        if (node.name().equals("NEXTVAL") && node.args().size() == 1) {
            out.token("nextval").raw("(");
            node.args().get(0).accept(this);
            out.raw(")");
            return null;
        }
        if (node.name().equals("POSITION") && !node.star() && node.args().size() == 2) {
            out.token("POSITION").raw("(");
            node.args().get(0).accept(this);
            out.token("IN");
            node.args().get(1).accept(this);
            out.raw(")");
            return null;
        }
        return super.visitFunctionCall(node);
    }

    @Override
    protected void renderRowLimit(Query query) {
        query.limit().ifPresent(limit -> {
            if (!limit.withTies()) {
                super.renderRowLimit(query);
                return;
            }
            // PG supports WITH TIES only on FETCH, not LIMIT.
            limit.offset().ifPresent(offset -> {
                out.token("OFFSET");
                offset.accept(this);
                out.token("ROWS");
            });
            out.token("FETCH FIRST");
            limit.count().ifPresentOrElse(
                    count -> count.accept(this),
                    () -> out.token("1"));
            out.token("ROWS").token("WITH").token("TIES");
        });
    }

    @Override
    protected String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @Override
    protected String renderStringLiteral(StringLiteral literal) {
        return "'" + literal.value().replace("'", "''") + "'";
    }

    @Override
    protected String concatOperator() {
        return "||";
    }

    @Override
    protected void renderDataType(DataType type) {
        String name = switch (type.type()) {
            case SMALLINT -> "SMALLINT";
            case INTEGER -> "INTEGER";
            case BIGINT -> "BIGINT";
            case DECIMAL -> "DECIMAL";
            case FLOAT -> "REAL";
            case DOUBLE -> "DOUBLE PRECISION";
            case CHAR -> "CHAR";
            case VARCHAR -> "VARCHAR";
            case TEXT -> "TEXT";
            case BOOLEAN -> "BOOLEAN";
            case DATE -> "DATE";
            case TIME -> "TIME";
            case TIMESTAMP -> "TIMESTAMP";
            case TIMESTAMP_TZ -> "TIMESTAMPTZ";
            case BLOB -> "BYTEA";
            case JSON -> "JSON";
            case JSONB -> "JSONB";
            case UUID -> "UUID";
            case TINYINT, NVARCHAR -> throw new IllegalStateException(
                    "rule engine contract: " + type.type() + " must not reach the PG printer");
        };
        out.token(name);
        renderTypeArgs(type);
        for (int i = 0; i < type.arrayDims(); i++) {
            out.raw("[]");
        }
    }

    @Override
    public Void visitColumnDefinition(ColumnDefinition node) {
        out.token(identifier(node.name()));
        if (node.autoIncrement()) {
            String serial = serialTypeName(node.type());
            if (serial != null) {
                out.token(serial);
            } else {
                renderDataType(node.type());
                renderAutoIncrement();
            }
        } else {
            renderDataType(node.type());
        }
        node.nullable().ifPresent(nullable -> out.token(nullable ? "NULL" : "NOT NULL"));
        node.defaultValue().ifPresent(value -> {
            out.token("DEFAULT");
            value.accept(this);
        });
        if (node.primaryKey()) {
            out.token("PRIMARY KEY");
        }
        if (node.unique()) {
            out.token("UNIQUE");
        }
        node.references().ifPresent(ref -> ref.accept(this));
        node.check().ifPresent(check -> {
            out.token("CHECK").raw("(");
            check.accept(this);
            out.raw(")");
        });
        renderGeneratedColumn(node);
        return null;
    }

    /** SERIAL family for integer types with auto-increment; null → use GENERATED … AS IDENTITY. */
    private static String serialTypeName(DataType type) {
        return switch (type.type()) {
            case SMALLINT -> "SMALLSERIAL";
            case INTEGER -> "SERIAL";
            case BIGINT -> "BIGSERIAL";
            default -> null;
        };
    }

    @Override
    protected void renderAutoIncrement() {
        // Insertable form in all directions (ROADMAP Phase 5): ALWAYS vs BY DEFAULT
        // both fold to ColumnDefinition.autoIncrement=true at build, so the printer
        // cannot recover ALWAYS. Matching AUTO_INCREMENT / IDENTITY insertability is
        // deliberate policy, including PG→PG — not a silent accidental rewrite.
        out.token("GENERATED BY DEFAULT AS IDENTITY");
    }
}
