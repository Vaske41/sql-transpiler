package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;

class CreateViewStatementTest {

    @Test
    void createOrReplaceViewBuildsOnPostgresql() {
        var script = AstBuilderFacade.buildScript(
                "CREATE OR REPLACE VIEW v (a, b) AS SELECT 1, 2;",
                Dialect.POSTGRESQL);
        var view = (rs.etf.sqltranslator.ast.CreateViewStatement) script.statements().get(0);
        assertThat(view.replaceOrAlter()).isTrue();
        assertThat(view.columns()).hasSize(2);
    }

    @Test
    void createOrReplaceViewTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "CREATE OR REPLACE VIEW findcount(season, team_count) AS "
                        + "SELECT season, 1 AS team_count FROM match;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("CREATE OR REPLACE VIEW");
        assertThat(sql).containsIgnoringCase("findcount");
    }

    @Test
    void createOrReplaceViewTowardTsqlUsesOrAlter() {
        String sql = CodegenTestSupport.printTranslated(
                "CREATE OR REPLACE VIEW v AS SELECT 1 AS x;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql).containsIgnoringCase("CREATE OR ALTER VIEW");
        assertThat(sql).doesNotContainIgnoringCase("REPLACE");
    }
}
