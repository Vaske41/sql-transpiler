package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class QuantifiedAndDerivedAliasTest {

    @Test
    void equalsAnySubqueryFoldsToInTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "UPDATE t SET active = false WHERE id = ANY (SELECT parent_id FROM t2);",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql.toUpperCase()).contains("IN");
        assertThat(sql.toUpperCase()).doesNotContain("ANY");
    }

    @Test
    void notEqualsAllFoldsToNotIn() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE id <> ALL (SELECT id FROM t2);",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql.toUpperCase()).contains("NOT IN");
    }

    @Test
    void derivedTableWithoutAliasSynthesizesAlias() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM (SELECT 1 AS x) WHERE x = 1;",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql();
        assertThat(sql.toUpperCase()).contains("AS _DT");
    }
}
