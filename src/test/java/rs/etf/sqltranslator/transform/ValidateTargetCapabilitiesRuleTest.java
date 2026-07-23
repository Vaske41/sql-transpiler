package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class ValidateTargetCapabilitiesRuleTest {

    private final Rule rule = new ValidateTargetCapabilitiesRule();

    @Test
    void fullJoinToMySqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT * FROM a FULL OUTER JOIN b ON a.x = b.x;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("FULL JOIN");
    }

    @Test
    void fullJoinToPostgresIsFine() {
        assertThatCode(() -> runRule(rule,
                "SELECT * FROM a FULL OUTER JOIN b ON a.x = b.x;",
                Dialect.TSQL, Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void offsetWithoutOrderByToTSqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT id FROM t LIMIT 3 OFFSET 5;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("OFFSET requires ORDER BY");
    }

    @Test
    void offsetWithOrderByToTSqlIsFine() {
        assertThatCode(() -> runRule(rule,
                "SELECT id FROM t ORDER BY id LIMIT 3 OFFSET 5;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .doesNotThrowAnyException();
    }

    @Test
    void offsetWithoutLimitToMySqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT id FROM t ORDER BY id OFFSET 5;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("OFFSET without LIMIT");
    }

    @Test
    void mysqlLooseGroupByWarnsForPostgresTarget() {
        TranslationResult r = runRule(rule,
                "SELECT dept, name FROM emp GROUP BY dept;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(r.report().warnings())
                .anyMatch(w -> w.code().equals("LOOSE_GROUP_BY")
                        && w.message().contains("name"));
    }

    @Test
    void mysqlStrictGroupByDoesNotWarn() {
        TranslationResult r = runRule(rule,
                "SELECT dept, COUNT(*) FROM emp GROUP BY dept;",
                Dialect.MYSQL, Dialect.POSTGRESQL);
        assertThat(r.report().warnings()).noneMatch(w -> w.code().equals("LOOSE_GROUP_BY"));
    }

    @Test
    void limitOverUnionWithoutOrderByToTSqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT id FROM t1 UNION ALL SELECT id FROM t2 LIMIT 5;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("UNION requires ORDER BY");
    }

    @Test
    void arrayAggToMysqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT ARRAY_AGG(x ORDER BY x) FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("ARRAY_AGG");
    }

    @Test
    void arrayAggToPostgresIsFine() {
        assertThatCode(() -> runRule(rule,
                "SELECT ARRAY_AGG(x ORDER BY x) FROM t;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }

    @Test
    void arrayTypeColonCastToMysqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT '{}'::text[] FROM t;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("array type");
    }

    @Test
    void arrayTypeColonCastToTsqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "SELECT '{}'::text[] FROM t;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("array type");
    }

    @Test
    void arrayTypeColonCastToPostgresIsFine() {
        assertThatCode(() -> runRule(rule,
                "SELECT '{}'::text[] FROM t;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }
}
