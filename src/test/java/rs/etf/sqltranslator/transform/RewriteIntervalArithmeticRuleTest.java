package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewriteIntervalArithmeticRuleTest {

    @Test
    void pgIntervalRendersNativelyTowardMysql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT INTERVAL '1 day' FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .contains("INTERVAL")
                .contains("1")
                .contains("DAY");
    }

    @Test
    void mysqlIntervalRendersNativelyTowardPostgresql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT INTERVAL 1 DAY FROM t;",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql())
                .contains("INTERVAL")
                .contains("'1 day'");
    }

    @Test
    void dateMinusIntervalBecomesDateAddTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT ts - INTERVAL '1 minute' FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("DATEADD")
                .contains("minute")
                .contains("-1")
                .doesNotContain("INTERVAL");
    }

    @Test
    void datePlusIntervalBecomesDateAddTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT birth_date + INTERVAL '16 years' FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("DATEADD")
                .contains("year")
                .contains("16")
                .doesNotContain("INTERVAL");
    }

    @Test
    void mysqlStringIntervalArithmeticTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT ts - INTERVAL '1' MINUTE FROM t;",
                Dialect.MYSQL, Dialect.TSQL).sql())
                .contains("DATEADD")
                .contains("minute")
                .contains("-1");
    }

    @Test
    void standaloneIntervalRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT INTERVAL '1 day' FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("INTERVAL");
    }

    @Test
    void compoundIntervalRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT ts - INTERVAL '1 day 03:00' FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("compound INTERVAL");
    }

    @Test
    void compoundIntervalRefusedTowardMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT INTERVAL '1 day 03:00' FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("compound INTERVAL");
    }
}
