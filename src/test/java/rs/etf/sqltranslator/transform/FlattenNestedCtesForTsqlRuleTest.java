package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;

import static org.assertj.core.api.Assertions.assertThat;

class FlattenNestedCtesForTsqlRuleTest {

    private static final String NESTED = """
            WITH outer_cte AS (
              WITH inner_cte AS (SELECT 1 AS x) SELECT inner_cte.x FROM inner_cte
            ) SELECT outer_cte.x FROM outer_cte;
            """;

    @Test
    void flattensNestedWithWhenTargetIsTsql() {
        String sql = Translator.translate(NESTED, Dialect.MYSQL, Dialect.TSQL).sql();
        assertThat(sql).doesNotContain("AS (WITH");
        assertThat(sql).contains("WITH inner_cte AS");
        assertThat(sql).contains("outer_cte AS");
    }

    @Test
    void leavesNestedWithWhenTargetAllowsIt() {
        String sql = Translator.translate(NESTED, Dialect.MYSQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).contains("AS (WITH");
    }
}
