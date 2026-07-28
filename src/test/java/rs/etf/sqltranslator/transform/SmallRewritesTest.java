package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmallRewritesTest {

    private String tr(String sql, Dialect from, Dialect to) {
        return Translator.translate(sql, from, to).sql();
    }

    @Test
    void fullJoinBecomesUnionOfOuterJoinsForMysql() {
        String sql = tr("SELECT * FROM a FULL OUTER JOIN b ON a.id = b.id",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(sql).containsIgnoringCase("LEFT JOIN")
                .containsIgnoringCase("UNION")
                .containsIgnoringCase("RIGHT JOIN")
                .containsIgnoringCase("WHERE a.id IS NULL");
    }

    @Test
    void fullJoinAntiKeyUsesLeftRelationWhenOnIsRightFirst() {
        String sql = tr("SELECT a.id, b.id FROM a FULL JOIN b ON b.k = a.k",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(sql).containsIgnoringCase("WHERE a.k IS NULL");
        assertThat(sql).doesNotContainIgnoringCase("WHERE b.k IS NULL");
    }

    @Test
    void fullJoinWithTrailingJoinRefusedForMysql() {
        assertThatThrownBy(() -> tr(
                "SELECT * FROM a FULL JOIN b ON a.k = b.k JOIN c ON c.k = a.k",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("joins after the FULL JOIN");
    }

    @Test
    void fullJoinWithAggregateRefusedForMysql() {
        assertThatThrownBy(() -> tr(
                "SELECT count(*) FROM a FULL JOIN b ON a.id = b.id",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("aggregation");
    }

    @Test
    void rowConstructorInBecomesExistsForTsql() {
        String sql = tr(
                "SELECT a FROM t WHERE (x, y) IN (SELECT p, q FROM u WHERE u.z > 5)",
                Dialect.POSTGRESQL, Dialect.TSQL);
        assertThat(sql).containsIgnoringCase("EXISTS");
        assertThat(sql).containsIgnoringCase("u.z > 5");
    }

    @Test
    void rowConstructorInWithLimitRefusedForTsql() {
        assertThatThrownBy(() -> tr(
                "SELECT a FROM t WHERE (x, y) IN (SELECT p, q FROM u ORDER BY p LIMIT 1)",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("ORDER BY or LIMIT");
    }

    @Test
    void atTimeZoneRefusedForMysql() {
        assertThatThrownBy(() -> tr("SELECT ts AT TIME ZONE 'UTC' FROM t",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("AT TIME ZONE");
    }

    @Test
    void deleteUsingBecomesTsqlDeleteFromJoin() {
        assertThat(tr("DELETE FROM a USING b WHERE a.id = b.id", Dialect.POSTGRESQL, Dialect.TSQL))
                .containsIgnoringCase("DELETE").containsIgnoringCase("FROM");
    }

    @Test
    void postgresqlUpdateReturningComesAfterWhere() {
        String sql = tr("UPDATE t SET a = 1 WHERE id = 2 RETURNING a",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL);
        assertThat(sql.toUpperCase().indexOf("WHERE"))
                .isLessThan(sql.toUpperCase().indexOf("RETURNING"));
    }

    @Test
    void postgresqlDeleteReturningComesAfterWhere() {
        String sql = tr("DELETE FROM t WHERE id = 2 RETURNING id",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL);
        assertThat(sql.toUpperCase().indexOf("WHERE"))
                .isLessThan(sql.toUpperCase().indexOf("RETURNING"));
    }
}
