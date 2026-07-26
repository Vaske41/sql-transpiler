package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural coverage for recursive CTEs: parse + print across dialects.
 * Semantic recursion validation is out of scope (coverage ≠ AccEX).
 */
class RecursiveCteRenderTest {

    @Test
    void pgRecursiveKeywordBuildsAndFlagsRecursive() {
        Script script = AstBuilderFacade.buildScript(
                "WITH RECURSIVE c AS (SELECT 1 AS x) SELECT * FROM c;", Dialect.POSTGRESQL);
        Query query = ((SelectStatement) script.statements().get(0)).query();
        assertThat(query.recursive()).isTrue();
    }

    @Test
    void tsqlSelfRefBuildsAndFlagsRecursive() {
        Script script = AstBuilderFacade.buildScript(
                "WITH c AS (SELECT * FROM c UNION ALL SELECT * FROM t) SELECT * FROM c;",
                Dialect.TSQL);
        Query query = ((SelectStatement) script.statements().get(0)).query();
        assertThat(query.recursive()).isTrue();
    }

    @Test
    void pgRecursiveRendersRecursiveForMysqlAndDropsForTsql() {
        String sql = "WITH RECURSIVE c AS (SELECT 1 AS x) SELECT * FROM c;";
        assertThat(Translator.translate(sql, Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .isEqualTo("WITH RECURSIVE c AS (SELECT 1 AS x) SELECT * FROM c;\n");
        assertThat(Translator.translate(sql, Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .isEqualTo("WITH c AS (SELECT 1 AS x) SELECT * FROM c;\n");
    }

    @Test
    void tsqlSelfRefAddsRecursiveWhenTargetingPostgresql() {
        String sql = "WITH c AS (SELECT * FROM c UNION ALL SELECT * FROM t) SELECT * FROM c;";
        assertThat(Translator.translate(sql, Dialect.TSQL, Dialect.POSTGRESQL).sql())
                .isEqualTo("WITH RECURSIVE c AS (SELECT * FROM c UNION ALL SELECT * FROM t) SELECT * FROM c;\n");
        assertThat(Translator.translate(sql, Dialect.TSQL, Dialect.MYSQL).sql())
                .isEqualTo("WITH RECURSIVE c AS (SELECT * FROM c UNION ALL SELECT * FROM t) SELECT * FROM c;\n");
    }
}
