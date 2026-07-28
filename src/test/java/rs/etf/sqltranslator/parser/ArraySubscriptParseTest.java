package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AbstractAstVisitor;
import rs.etf.sqltranslator.ast.ArrayLiteral;
import rs.etf.sqltranslator.ast.ArraySubscript;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL postfix {@code ::} casts vs {@code [subscript]} disambiguation. */
class ArraySubscriptParseTest {

    @Test
    void textArrayColonCastCarriesArrayDims() {
        CastExpression cast = firstCast("SELECT x::text[] FROM t;", Dialect.POSTGRESQL);
        assertThat(cast.targetType().type()).isEqualTo(GenericType.TEXT);
        assertThat(cast.targetType().arrayDims()).isEqualTo(1);
    }

    @Test
    void colonCastThenSubscriptParses() {
        ArraySubscript sub = firstSubscript("SELECT x::text[1] FROM t;", Dialect.POSTGRESQL);
        assertThat(sub.base()).isInstanceOf(CastExpression.class);
        CastExpression cast = (CastExpression) sub.base();
        assertThat(cast.targetType().type()).isEqualTo(GenericType.TEXT);
        assertThat(cast.targetType().arrayDims()).isEqualTo(0);
    }

    @Test
    void subscriptOnArrayLiteralParses() {
        ArraySubscript sub = firstSubscript(
                "SELECT (ARRAY[1, 2])[1] FROM t;", Dialect.POSTGRESQL);
        assertThat(sub.base()).isInstanceOf(ArrayLiteral.class);
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

    private static ArraySubscript firstSubscript(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        List<ArraySubscript> subs = new ArrayList<>();
        script.accept(new AbstractAstVisitor<Void>() {
            @Override
            public Void visitArraySubscript(ArraySubscript node) {
                subs.add(node);
                return super.visitArraySubscript(node);
            }
        });
        assertThat(subs).isNotEmpty();
        return subs.get(0);
    }
}
