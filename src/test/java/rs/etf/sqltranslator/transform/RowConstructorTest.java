package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowConstructorTest {

    @Test
    void rowInSubqueryBuildsOnPostgresql() {
        var script = AstBuilderFacade.buildScript(
                "SELECT * FROM t WHERE (a, b) IN (SELECT x, y FROM u);",
                Dialect.POSTGRESQL);
        assertThat(script.statements()).hasSize(1);
    }

    @Test
    void rowInSubqueryPrintsTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT learner_code FROM resource_usage "
                        + "WHERE (learner_code, usage_date) IN "
                        + "(SELECT learner_code, MAX(usage_date) FROM resource_usage GROUP BY learner_code);",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).contains("(learner_code, usage_date)");
        assertThat(sql).containsIgnoringCase("IN");
    }

    @Test
    void rowConstructorRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE (a, b) IN (SELECT x, y FROM u);",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("row constructor");
    }
}
