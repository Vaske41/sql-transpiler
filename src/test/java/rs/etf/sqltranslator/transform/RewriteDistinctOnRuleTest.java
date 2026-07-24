package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewriteDistinctOnRuleTest {

    @Test
    void distinctOnBuildsOnPostgresql() {
        var script = AstBuilderFacade.buildScript(
                "SELECT DISTINCT ON (name) raceid, name, date FROM races ORDER BY name, date DESC;",
                Dialect.POSTGRESQL);
        var spec = script.statements().get(0) instanceof rs.etf.sqltranslator.ast.SelectStatement s
                ? s.query().first() : null;
        assertThat(spec).isNotNull();
        assertThat(spec.distinctOn()).hasSize(1);
    }

    @Test
    void distinctOnRewritesToRowNumberTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT DISTINCT ON (name) raceid, name, date FROM races ORDER BY name, date DESC;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("ROW_NUMBER()");
        assertThat(sql).containsIgnoringCase("PARTITION BY");
        assertThat(sql).containsIgnoringCase("_rn");
        assertThat(sql).doesNotContainIgnoringCase("DISTINCT ON");
    }

    @Test
    void distinctOnRewritesToRowNumberTowardTsql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT DISTINCT ON (name) raceid, name, date FROM races ORDER BY name, date DESC;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql).containsIgnoringCase("ROW_NUMBER()");
        assertThat(sql).containsIgnoringCase("PARTITION BY");
        assertThat(sql).doesNotContainIgnoringCase("DISTINCT ON");
    }

    @Test
    void distinctOnPreservedTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT DISTINCT ON (name) raceid, name, date FROM races ORDER BY name, date DESC;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("DISTINCT ON");
        assertThat(sql).doesNotContainIgnoringCase("ROW_NUMBER()");
    }

    @Test
    void distinctOnWithoutOrderByRefusedTowardMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "SELECT DISTINCT ON (account_id) trans_id, account_id FROM trans;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("DISTINCT ON without ORDER BY");
    }
}
