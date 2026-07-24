package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.ast.TableFunction;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableFunctionRelationTest {

    @Test
    void generateSeriesAsFromPrimaryBuilds() {
        var script = AstBuilderFacade.buildScript(
                "SELECT * FROM generate_series(1, 3) AS g;",
                Dialect.POSTGRESQL);
        var select = (SelectStatement) script.statements().get(0);
        TableSource from = select.query().first().from().orElseThrow();
        assertThat(from.first()).isInstanceOf(TableFunction.class);
        TableFunction fn = (TableFunction) from.first();
        assertThat(fn.name().last().value()).isEqualToIgnoringCase("generate_series");
        assertThat(fn.args()).hasSize(2);
        assertThat(fn.alias()).isPresent();
    }

    @Test
    void generateSeriesCrossJoinPreservedTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT d::date FROM race_incidents T "
                        + "CROSS JOIN generate_series(T.incident_start, T.incident_end, "
                        + "INTERVAL '1 day') d;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("generate_series");
        assertThat(sql).containsIgnoringCase("CROSS JOIN");
    }

    @Test
    void generateSeriesCommaJoinTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t, generate_series(1, 2) g;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("generate_series");
    }

    @Test
    void jsonArrayElementsAsTableTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT tt.value FROM JSON_ARRAY_ELEMENTS(t.events) AS tt;",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("JSON_ARRAY_ELEMENTS");
    }

    @Test
    void tableFunctionRefusedTowardMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT * FROM generate_series(1, 3) g;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("table function");
    }

    @Test
    void tableFunctionRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT * FROM generate_series(1, 3) g;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("table function");
    }
}
