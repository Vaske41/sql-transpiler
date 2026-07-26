package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LATERAL ↔ CROSS/OUTER APPLY rendering and refusal. */
class RenderLateralApplyTest {

    @Test
    void postgresLateralRendersNativeTowardMysql() {
        String out = CodegenTestSupport.printTranslated("""
                SELECT u.id, r.x
                FROM users u
                CROSS JOIN LATERAL (SELECT u.id AS x) AS r;
                """, Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(out).containsIgnoringCase("LATERAL");
        assertThat(out).doesNotContainIgnoringCase("APPLY");
    }

    @Test
    void crossJoinLateralBecomesCrossApplyTowardTsql() {
        String out = CodegenTestSupport.printTranslated("""
                SELECT u.id, r.x
                FROM users u
                CROSS JOIN LATERAL (SELECT u.id AS x) AS r;
                """, Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(out).containsIgnoringCase("CROSS APPLY");
        assertThat(out).doesNotContainIgnoringCase("LATERAL");
        assertThat(out).doesNotContainIgnoringCase("JOIN");
    }

    @Test
    void leftJoinLateralOnTrueBecomesOuterApplyTowardTsql() {
        String out = CodegenTestSupport.printTranslated("""
                SELECT u.id, r.x
                FROM users u
                LEFT JOIN LATERAL (SELECT u.id AS x) AS r ON TRUE;
                """, Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(out).containsIgnoringCase("OUTER APPLY");
        assertThat(out).doesNotContainIgnoringCase("LATERAL");
        assertThat(out).doesNotContainIgnoringCase(" ON ");
    }

    @Test
    void crossApplyBecomesCrossJoinLateralTowardPostgres() {
        String out = CodegenTestSupport.printTranslated("""
                SELECT u.id, r.x
                FROM users u
                CROSS APPLY (SELECT u.id AS x) AS r;
                """, Dialect.TSQL, Dialect.POSTGRESQL).sql();
        assertThat(out).containsIgnoringCase("CROSS JOIN LATERAL");
        assertThat(out).doesNotContainIgnoringCase("APPLY");
    }

    @Test
    void leftJoinLateralWithPredicateRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated("""
                SELECT u.id, r.x
                FROM users u
                LEFT JOIN LATERAL (SELECT 1 AS x) AS r ON u.id = r.x;
                """, Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("LATERAL");
    }
}
