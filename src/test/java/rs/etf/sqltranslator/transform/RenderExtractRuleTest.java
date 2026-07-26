package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderExtractRuleTest {

    @Test
    void yearExtractRendersNativelyTowardMysql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT EXTRACT(YEAR FROM d) FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .contains("EXTRACT")
                .contains("YEAR")
                .contains("FROM");
    }

    @Test
    void yearExtractRendersNativelyTowardPostgresql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT EXTRACT(YEAR FROM d) FROM t;",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql())
                .contains("EXTRACT")
                .contains("YEAR");
    }

    @Test
    void yearExtractBecomesDatePartTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT EXTRACT(YEAR FROM d) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("DATEPART")
                .contains("YEAR")
                .doesNotContain("EXTRACT");
    }

    @Test
    void epochExtractBecomesDateDiffTowardTsql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT EXTRACT(EPOCH FROM ts) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("DATEDIFF")
                .contains("SECOND")
                .contains("19700101")
                .doesNotContain("EXTRACT");
    }

    @Test
    void dowExtractRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT EXTRACT(DOW FROM d) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("EXTRACT(DOW)");
    }

    @Test
    void isodowExtractRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT EXTRACT(ISODOW FROM d) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("EXTRACT(ISODOW)");
    }

    @Test
    void isoyearExtractRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT EXTRACT(ISOYEAR FROM d) FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("EXTRACT(ISOYEAR)");
    }
}
