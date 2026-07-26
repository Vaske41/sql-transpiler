package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExceptIntersectTest {

    @Test
    void exceptParsesOnPostgresql() {
        assertThatCode(() -> AstBuilderFacade.buildScript(
                "SELECT 1 EXCEPT SELECT 2;",
                Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void exceptPrintsTowardMysqlAndTsql() {
        String toMysql = CodegenTestSupport.printTranslated(
                "SELECT id FROM a EXCEPT SELECT id FROM b;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(toMysql).containsIgnoringCase("EXCEPT");

        String toTsql = CodegenTestSupport.printTranslated(
                "SELECT id FROM a EXCEPT SELECT id FROM b;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(toTsql).containsIgnoringCase("EXCEPT");
    }

    @Test
    void intersectParsesAndPrints() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT id FROM a INTERSECT SELECT id FROM b;",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("INTERSECT");
    }

    @Test
    void exceptAllTowardTsqlIsRefused() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT 1 EXCEPT ALL SELECT 2;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("EXCEPT ALL");
    }
}
