package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArrayAndAtTimeZoneTest {

    @Test
    void arrayLiteralBuildsOnPostgresql() {
        var script = AstBuilderFacade.buildScript(
                "SELECT ARRAY[1, 2, 3] AS a FROM t;", Dialect.POSTGRESQL);
        assertThat(new rs.etf.sqltranslator.ast.AstDumper().dump(script))
                .contains("ArrayLiteral");
    }

    @Test
    void arrayLiteralPreservedTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT ARRAY[1, 2] AS a;", Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("ARRAY");
        assertThat(sql).contains("[");
    }

    @Test
    void arrayLiteralRefusedTowardMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT ARRAY[1, 2] AS a;", Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("ARRAY");
    }

    @Test
    void atTimeZoneBuildsOnPostgresql() {
        var script = AstBuilderFacade.buildScript(
                "SELECT ts AT TIME ZONE 'UTC' FROM t;", Dialect.POSTGRESQL);
        var q = (rs.etf.sqltranslator.ast.SelectStatement) script.statements().get(0);
        assertThat(new rs.etf.sqltranslator.ast.AstDumper().dump(q)).contains("AtTimeZone");
    }

    @Test
    void atTimeZonePreservedTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT ts AT TIME ZONE 'UTC' FROM t;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("AT TIME ZONE");
    }

    @Test
    void atTimeZoneRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT ts AT TIME ZONE 'UTC' FROM t;", Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("AT TIME ZONE");
    }
}
