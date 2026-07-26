package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AbstractAstVisitor;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SortDirection;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionCallOrderByTest {

    @Test
    void postgresArrayAggOrderByParsesWithOrderItems() {
        Script s = AstBuilderFacade.buildScript(
                "SELECT ARRAY_AGG(x ORDER BY x DESC) FROM t;", Dialect.POSTGRESQL);
        FunctionCall call = firstFunctionCall(s);
        assertThat(call.name()).isEqualTo("ARRAY_AGG");
        assertThat(call.orderBy()).hasSize(1);
        assertThat(call.orderBy().get(0).direction()).isEqualTo(SortDirection.DESC);
    }

    private static FunctionCall firstFunctionCall(Script script) {
        FunctionCall[] found = new FunctionCall[1];
        script.accept(new AbstractAstVisitor<Void>() {
            @Override
            public Void visitFunctionCall(FunctionCall node) {
                if (found[0] == null) {
                    found[0] = node;
                }
                return super.visitFunctionCall(node);
            }
        });
        assertThat(found[0]).isNotNull();
        return found[0];
    }
}
