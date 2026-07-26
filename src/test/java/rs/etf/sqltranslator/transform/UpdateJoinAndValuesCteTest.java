package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class UpdateJoinAndValuesCteTest {

    @Test
    void mysqlUpdateJoinParsesAndRewritesTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "UPDATE Laboratory l JOIN Patient p ON p.ID = l.ID "
                        + "SET l.TAT = 1 WHERE p.SEX = 'F';",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("UPDATE");
        assertThat(sql).containsIgnoringCase("FROM");
        assertThat(sql).containsIgnoringCase("SET");
        assertThat(sql).containsIgnoringCase("WHERE");
        assertThat(sql).doesNotContainIgnoringCase(" JOIN ");
    }

    @Test
    void mysqlUpdateJoinTowardMysqlUsesCommaForm() {
        String sql = CodegenTestSupport.printTranslated(
                "UPDATE Laboratory AS l JOIN Patient AS p ON p.ID = l.ID "
                        + "SET l.TAT = 1 WHERE p.SEX = 'F';",
                Dialect.MYSQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("UPDATE");
        assertThat(sql).contains(",");
        assertThat(sql).containsIgnoringCase("SET");
        assertThat(sql).containsIgnoringCase("WHERE");
    }

    @Test
    void valuesCteBodyParsesOnPostgresql() {
        assertThatCode(() -> AstBuilderFacade.buildScript(
                "WITH src(id, geom) AS (VALUES (1, 'a'), (2, 'b')) SELECT * FROM src;",
                Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void valuesCteBodyPrintsTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "WITH src(id, v) AS (VALUES (1, 'a'), (2, 'b')) SELECT id FROM src;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("WITH");
        assertThat(sql).containsIgnoringCase("VALUES");
        assertThat(sql).containsIgnoringCase("SELECT");
    }
}
