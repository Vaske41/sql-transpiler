package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;

class ExpandJoinUsingForTsqlRuleTest {

    @Test
    void usingJoinBuildsOnPostgresql() {
        var script = AstBuilderFacade.buildScript(
                "SELECT * FROM a JOIN b USING (id);", Dialect.POSTGRESQL);
        var select = (rs.etf.sqltranslator.ast.SelectStatement) script.statements().get(0);
        var join = select.query().first().from().orElseThrow().joins().get(0);
        assertThat(join.usingColumns()).hasSize(1);
        assertThat(join.on()).isEmpty();
    }

    @Test
    void usingPreservedTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM a AS t1 JOIN b AS t2 USING (id);",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("USING");
        assertThat(sql).containsIgnoringCase("id");
    }

    @Test
    void usingExpandedTowardTsql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM a AS t1 JOIN b AS t2 USING (id);",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql).doesNotContainIgnoringCase("USING");
        assertThat(sql).containsIgnoringCase("ON");
        assertThat(sql).containsIgnoringCase("t1.id");
        assertThat(sql).containsIgnoringCase("t2.id");
    }

    @Test
    void multiColumnUsingExpandedTowardTsql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM purchase JOIN sale USING (nth_operation, molecule_id);",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql).doesNotContainIgnoringCase("USING");
        assertThat(sql).containsIgnoringCase("purchase.nth_operation");
        assertThat(sql).containsIgnoringCase("sale.molecule_id");
    }
}
