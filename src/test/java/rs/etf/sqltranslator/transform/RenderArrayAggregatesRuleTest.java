package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderArrayAggregatesRuleTest {

    @Test
    void arrayAggBecomesJsonArrayAggForMysql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT array_agg(x) FROM t;", Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .containsIgnoringCase("JSON_ARRAYAGG");
    }

    @Test
    void arrayAggStillRefusesForTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT array_agg(x) FROM t;", Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("no array type in target");
    }

    @Test
    void distinctArrayAggRefusedForMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT array_agg(DISTINCT x) FROM t;", Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("distinct");
    }

    @Test
    void orderedArrayAggRefusedForMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT array_agg(x ORDER BY x) FROM t;", Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("ordered");
    }
}
