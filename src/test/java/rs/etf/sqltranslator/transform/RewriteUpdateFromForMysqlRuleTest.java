package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class RewriteUpdateFromForMysqlRuleTest {

    private final Rule rule = new RewriteUpdateFromForMysqlRule();

    @Test
    void mysqlCommaJoinQualifiesSetLhsAndDropsFromKeyword() {
        String sql = """
                UPDATE card AS c
                SET disp_id = sub.disp_id
                FROM (SELECT disp_id, type FROM card WHERE disp_id = 41) AS sub
                WHERE c.type = sub.type;
                """;
        String out = CodegenTestSupport.printTranslated(sql, Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(out)
                .contains("UPDATE")
                .contains("card")
                .contains(",")
                .contains("SET")
                .contains("c.disp_id")
                .contains("WHERE");
        // MySQL multi-table form: relations before SET; no UPDATE…FROM clause after SET.
        int setAt = out.indexOf("SET");
        assertThat(setAt).isPositive();
        assertThat(out.indexOf("FROM", setAt)).isNegative();
    }

    @Test
    void postgresqlKeepsNativeFrom() {
        String sql = """
                UPDATE card AS c
                SET disp_id = sub.disp_id
                FROM (SELECT disp_id FROM card WHERE disp_id = 41) AS sub
                WHERE c.disp_id = sub.disp_id;
                """;
        assertThat(CodegenTestSupport.printTranslated(
                sql, Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql())
                .contains("FROM")
                .contains("SET");
    }

    @Test
    void tsqlKeepsNativeFrom() {
        String sql = """
                UPDATE card AS c
                SET disp_id = sub.disp_id
                FROM (SELECT disp_id FROM card WHERE disp_id = 41) AS sub
                WHERE c.disp_id = sub.disp_id;
                """;
        assertThat(CodegenTestSupport.printTranslated(sql, Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .contains("FROM")
                .contains("SET");
    }

    @Test
    void ruleQualifiesAssignmentsTowardMysql() {
        TranslationResult r = runRule(rule, """
                UPDATE t AS x SET c = src.v FROM src WHERE x.id = src.id;
                """, Dialect.POSTGRESQL, Dialect.MYSQL);
        UpdateStatement upd = (UpdateStatement) r.script().statements().get(0);
        assertThat(upd.from()).isPresent();
        Assignment a = upd.assignments().get(0);
        assertThat(a.column().parts()).hasSize(2);
        assertThat(a.column().parts().get(0).value()).isEqualToIgnoringCase("x");
        assertThat(a.column().last().value()).isEqualToIgnoringCase("c");
    }

    @Test
    void selfJoinTowardMysqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "UPDATE t SET c = t2.c FROM t AS t2 WHERE t.id = t2.id;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("UPDATE ... FROM self-join to MySQL");
    }

    @Test
    void selfJoinTowardPostgresqlIsFine() {
        assertThatCode(() -> runRule(rule,
                "UPDATE t SET c = t2.c FROM t AS t2 WHERE t.id = t2.id;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void joinInFromStillRewritesTowardMysql() {
        String sql = """
                UPDATE superhero AS s
                SET full_name = 'Superman'
                FROM team_member_superhero AS tms
                JOIN team_member AS tm ON tms.team_member_id = tm.id
                WHERE s.id = tms.superhero_id AND tm.team_id = 91;
                """;
        String out = CodegenTestSupport.printTranslated(sql, Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(out)
                .contains("UPDATE")
                .contains("superhero")
                .contains("s.full_name")
                .contains("JOIN");
        int setAt = out.indexOf("SET");
        assertThat(setAt).isPositive();
        assertThat(out.indexOf("FROM", setAt)).isNegative();
    }
}
