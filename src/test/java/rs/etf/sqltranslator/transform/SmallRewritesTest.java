package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;

import static org.assertj.core.api.Assertions.assertThat;

class SmallRewritesTest {

    private String tr(String sql, Dialect from, Dialect to) {
        return Translator.translate(sql, from, to).sql();
    }

    @Test
    void fullJoinBecomesUnionOfOuterJoinsForMysql() {
        assertThat(tr("SELECT * FROM a FULL OUTER JOIN b ON a.id = b.id",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .containsIgnoringCase("LEFT JOIN")
                .containsIgnoringCase("UNION")
                .containsIgnoringCase("RIGHT JOIN");
    }

    @Test
    void rowConstructorInBecomesExistsForTsql() {
        assertThat(tr("SELECT a FROM t WHERE (x, y) IN (SELECT p, q FROM u)",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .containsIgnoringCase("EXISTS");
    }

    @Test
    void atTimeZoneBecomesConvertTzForMysql() {
        assertThat(tr("SELECT ts AT TIME ZONE 'UTC' FROM t", Dialect.POSTGRESQL, Dialect.MYSQL))
                .containsIgnoringCase("CONVERT_TZ");
    }

    @Test
    void deleteUsingBecomesTsqlDeleteFromJoin() {
        assertThat(tr("DELETE FROM a USING b WHERE a.id = b.id", Dialect.POSTGRESQL, Dialect.TSQL))
                .containsIgnoringCase("DELETE").containsIgnoringCase("FROM");
    }
}
