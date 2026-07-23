package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderJsonRuleTest {

    @Test
    void arrowTextNativeTowardMysql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT data ->> 'id' FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .contains("->>")
                .contains("'$.id'")
                .doesNotContain("->> 'id'");
    }

    @Test
    void mysqlDollarPathNotDoublePrefixedTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT data ->> '$.id' FROM t;",
                Dialect.MYSQL, Dialect.TSQL).sql())
                .contains("JSON_VALUE")
                .contains("'$.id'")
                .doesNotContain("'$.$.id'");
    }

    @Test
    void arrowTextBecomesJsonValueTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT data ->> 'id' FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("JSON_VALUE")
                .contains("'$.id'")
                .doesNotContain("->>");
    }

    @Test
    void arrowObjectBecomesJsonQueryTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT data -> 'k' FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("JSON_QUERY")
                .contains("'$.k'")
                .doesNotContain("->");
    }

    @Test
    void hashPathCollapsesTowardMysql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT col #> '{a,b}' FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .contains("->")
                .contains("'$.a.b'")
                .doesNotContain("#>")
                .doesNotContain("-> 'a'");
    }

    @Test
    void hashPathTextBecomesJsonValueTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT col #>> '{a,b}' FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("JSON_VALUE")
                .contains("'$.a.b'")
                .doesNotContain("#>>");
    }

    @Test
    void containmentRefusedTowardMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT payload @> '{\"a\":1}' FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("containment");
    }

    @Test
    void containmentRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT payload @> '{\"a\":1}' FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("containment");
    }

    @Test
    void nonLiteralKeyRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT data ->> key_col FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("non-literal");
    }
}
