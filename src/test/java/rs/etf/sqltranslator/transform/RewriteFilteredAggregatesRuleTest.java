package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;
import static rs.etf.sqltranslator.transform.TransformTestSupport.selectExpr;

class RewriteFilteredAggregatesRuleTest {

    private final Rule rule = new RewriteFilteredAggregatesRule();

    @Test
    void filterRewritesToCaseTowardMysql() {
        TranslationResult r = runRule(rule,
                "SELECT COUNT(*) FILTER (WHERE active = TRUE) FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(new AstDumper().dump(r.script()))
                .contains("CaseExpression").doesNotContain("filter=");
    }

    @Test
    void filterRewritesToCaseTowardTsql() {
        TranslationResult r = runRule(rule,
                "SELECT COUNT(*) FILTER (WHERE active = TRUE) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        FunctionCall call = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(call.filter()).isEmpty();
        assertThat(call.star()).isFalse();
        assertThat(call.args()).hasSize(1);
        assertThat(new AstDumper().dump(call.args().get(0))).contains("CaseExpression");
    }

    @Test
    void filterPreservedTowardPostgresql() {
        TranslationResult r = runRule(rule,
                "SELECT COUNT(*) FILTER (WHERE active = TRUE) FROM t;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL);
        FunctionCall call = (FunctionCall) selectExpr(r.script(), 0, 0);
        assertThat(call.filter()).isPresent();
        assertThat(call.star()).isTrue();
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT COUNT(*) FILTER (WHERE active = TRUE) FROM t;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql())
                .contains("FILTER")
                .contains("WHERE");
    }

    @Test
    void countStarFilterPrintsCaseTowardMysql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT COUNT(*) FILTER (WHERE active = TRUE) FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .contains("COUNT")
                .contains("CASE")
                .contains("WHEN")
                .doesNotContain("FILTER");
    }
}
