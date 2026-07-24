package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Curated keyword tokens usable as column / alias names in identifier positions.
 */
class NonReservedIdentifierTest {

    @Test
    void keywordValueUsableAsColumnName() {
        Script s = AstBuilderFacade.buildScript(
                "SELECT t.value, t.key FROM kv AS t;", Dialect.POSTGRESQL);
        assertThat(new AstDumper().dump(s)).contains("value").contains("key");
    }

    @Test
    void keywordEndUsableAsQualifiedColumn() {
        Script s = AstBuilderFacade.buildScript(
                "SELECT j.end FROM jobs AS j;", Dialect.POSTGRESQL);
        assertThat(new AstDumper().dump(s)).contains("end");
    }

    @Test
    void keywordFirstLastUsableAsColumnsAndAliases() {
        Script s = AstBuilderFacade.buildScript(
                "SELECT t.first AS last FROM hist AS t;", Dialect.MYSQL);
        String dump = new AstDumper().dump(s);
        assertThat(dump).contains("first").contains("last");
    }

    @Test
    void caseExpressionStillParsesWithEndKeyword() {
        assertThatCode(() -> AstBuilderFacade.buildScript(
                "SELECT CASE WHEN x = 1 THEN 'a' ELSE 'b' END FROM t;",
                Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void nullsFirstStillParses() {
        assertThatCode(() -> AstBuilderFacade.buildScript(
                "SELECT a FROM t ORDER BY a NULLS FIRST;",
                Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void endUsableAsCreateTableColumn() {
        assertThatCode(() -> AstBuilderFacade.buildScript(
                "CREATE TABLE table1 (id INT, end INT NOT NULL);",
                Dialect.MYSQL))
                .doesNotThrowAnyException();
    }

    @Test
    void rowUsableAsAlias() {
        Script s = AstBuilderFacade.buildScript(
                "SELECT 1 AS row FROM t;", Dialect.POSTGRESQL);
        assertThat(new AstDumper().dump(s)).contains("row");
    }
}
