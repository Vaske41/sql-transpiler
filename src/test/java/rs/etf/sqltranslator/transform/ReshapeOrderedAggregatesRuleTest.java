package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class ReshapeOrderedAggregatesRuleTest {

    private final Rule rule = new ReshapeOrderedAggregatesRule();

    @Test
    void stringAggToMysqlBecomesGroupConcatWithSeparator() {
        TranslationResult r = runRule(rule,
                "SELECT STRING_AGG(name, ',' ORDER BY name) FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        FunctionCall call = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(call.name()).isEqualTo("GROUP_CONCAT");
        assertThat(call.args()).hasSize(2);
        assertThat(call.orderBy()).hasSize(1);
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT STRING_AGG(name, ',' ORDER BY name) FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .contains("GROUP_CONCAT")
                .contains("ORDER BY")
                .contains("SEPARATOR");
    }

    @Test
    void groupConcatToPostgresBecomesStringAgg() {
        TranslationResult r = runRule(rule,
                "SELECT GROUP_CONCAT(id2 ORDER BY id2) FROM t;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        FunctionCall call = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(call.name()).isEqualTo("STRING_AGG");
        assertThat(call.args()).hasSize(2);
        assertThat(call.orderBy()).hasSize(1);
    }

    @Test
    void stringAggToTsqlKeepsNameAndPrintsWithinGroup() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT STRING_AGG(name, ',' ORDER BY name) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("STRING_AGG")
                .contains("WITHIN GROUP")
                .contains("ORDER BY");
    }

    @Test
    void orderedNonStringAggToTsqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT MAX(x ORDER BY y) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("ordered aggregate MAX");
    }

    @Test
    void distinctGroupConcatToTsqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT GROUP_CONCAT(DISTINCT id2 ORDER BY id2) FROM t;",
                Dialect.MYSQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("STRING_AGG DISTINCT");
    }

    @Test
    void distinctStringAggToTsqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT STRING_AGG(DISTINCT name, ',' ORDER BY name) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("STRING_AGG DISTINCT");
    }
}
