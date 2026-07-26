package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AbstractAstVisitor;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.FixedLength;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PostgreSQL {@code ::} casts: residual positions and array-type targets.
 */
class PgColonCastTest {

    @Test
    void textArrayColonCastCarriesArrayDims() {
        CastExpression cast = firstCast(
                "SELECT '{}'::text[] FROM t;", Dialect.POSTGRESQL);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.TEXT);
        assertThat(cast.targetType().arrayDims()).isEqualTo(1);
    }

    @Test
    void multiDimArrayColonCastCountsDims() {
        CastExpression cast = firstCast(
                "SELECT x::integer[][] FROM t;", Dialect.POSTGRESQL);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.INTEGER);
        assertThat(cast.targetType().arrayDims()).isEqualTo(2);
    }

    @Test
    void colonCastAfterFunctionCallParses() {
        CastExpression cast = firstCast(
                "SELECT upper(name)::text FROM t;", Dialect.POSTGRESQL);
        assertThat(cast.operand()).isInstanceOf(FunctionCall.class);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.TEXT);
        assertThat(cast.targetType().arrayDims()).isEqualTo(0);
    }

    @Test
    void colonCastAfterParenthesizedExprParses() {
        CastExpression cast = firstCast(
                "SELECT (id + 1)::integer FROM t;", Dialect.POSTGRESQL);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.INTEGER);
    }

    @Test
    void colonCastWithNumericPrecisionParses() {
        CastExpression cast = firstCast(
                "SELECT col::numeric(10,2) FROM t;", Dialect.POSTGRESQL);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.DECIMAL);
        assertThat(cast.targetType().length()).contains(new FixedLength(10));
        assertThat(cast.targetType().scale()).contains(2);
        assertThat(cast.targetType().arrayDims()).isEqualTo(0);
    }

    @Test
    void qualifiedColumnColonCastParses() {
        CastExpression cast = firstCast(
                "SELECT p.uuid::varchar FROM t p;", Dialect.POSTGRESQL);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.VARCHAR);
    }

    private static CastExpression firstCast(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        List<CastExpression> casts = new ArrayList<>();
        script.accept(new AbstractAstVisitor<Void>() {
            @Override
            public Void visitCastExpression(CastExpression node) {
                casts.add(node);
                return super.visitCastExpression(node);
            }
        });
        assertThat(casts).isNotEmpty();
        return casts.get(0);
    }
}
