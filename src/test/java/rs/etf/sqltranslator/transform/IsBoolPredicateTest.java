package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class IsBoolPredicateTest {

    @Test
    void isTrueNativeTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE flag IS TRUE;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("IS TRUE");
    }

    @Test
    void isTrueRewritesTowardTsql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE flag IS TRUE;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql).doesNotContainIgnoringCase("IS TRUE");
        assertThat(sql).contains("=");
    }

    @Test
    void isUnknownBecomesIsNullTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE flag IS UNKNOWN;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("IS NULL");
        assertThat(sql).doesNotContainIgnoringCase("UNKNOWN");
    }

    @Test
    void isUnknownBecomesIsNullTowardTsql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE flag IS UNKNOWN;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql).containsIgnoringCase("IS NULL");
    }

    @Test
    void isNotFalseTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE flag IS NOT FALSE;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("IS NOT FALSE");
    }
}
