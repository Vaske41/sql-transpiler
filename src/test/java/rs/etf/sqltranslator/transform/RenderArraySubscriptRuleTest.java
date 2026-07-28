package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class RenderArraySubscriptRuleTest {

    @Test
    void oneBasedPgSubscriptBecomesZeroBasedJsonPath() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT tags[1] FROM t", Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .contains("'$[0]'");
    }

    @Test
    void subscriptOnAggregateResultParses() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT (ARRAY_AGG(e ORDER BY id))[1] FROM t GROUP BY a",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql())
                .containsIgnoringCase("ARRAY_AGG");
    }
}
