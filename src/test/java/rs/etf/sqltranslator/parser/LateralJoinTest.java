package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AbstractAstVisitor;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

/** LATERAL / CROSS APPLY / OUTER APPLY join shapes. */
class LateralJoinTest {

    @Test
    void postgresCrossJoinLateralSetsFlag() {
        Join join = firstJoin("""
                SELECT u.id, r.x
                FROM users u
                CROSS JOIN LATERAL (SELECT u.id AS x) AS r;
                """, Dialect.POSTGRESQL);
        assertThat(join.kind()).isEqualTo(JoinKind.CROSS);
        assertThat(join.lateral()).isTrue();
        assertThat(join.on()).isEmpty();
    }

    @Test
    void mysqlLeftJoinLateralOnTrueSetsFlag() {
        Join join = firstJoin("""
                SELECT u.id, r.x
                FROM users u
                LEFT JOIN LATERAL (SELECT u.id AS x) AS r ON TRUE;
                """, Dialect.MYSQL);
        assertThat(join.kind()).isEqualTo(JoinKind.LEFT);
        assertThat(join.lateral()).isTrue();
        assertThat(join.on()).isPresent();
    }

    @Test
    void tsqlCrossApplyIsLateralCross() {
        Join join = firstJoin("""
                SELECT u.id, r.x
                FROM users u
                CROSS APPLY (SELECT u.id AS x) AS r;
                """, Dialect.TSQL);
        assertThat(join.kind()).isEqualTo(JoinKind.CROSS);
        assertThat(join.lateral()).isTrue();
        assertThat(join.on()).isEmpty();
    }

    @Test
    void tsqlOuterApplyIsLateralLeft() {
        Join join = firstJoin("""
                SELECT u.id, r.x
                FROM users u
                OUTER APPLY (SELECT u.id AS x) AS r;
                """, Dialect.TSQL);
        assertThat(join.kind()).isEqualTo(JoinKind.LEFT);
        assertThat(join.lateral()).isTrue();
    }

    @Test
    void commaLateralIsLateralCross() {
        Join join = firstJoin("""
                SELECT u.id, r.x
                FROM users u, LATERAL (SELECT u.id AS x) AS r;
                """, Dialect.POSTGRESQL);
        assertThat(join.kind()).isEqualTo(JoinKind.CROSS);
        assertThat(join.lateral()).isTrue();
    }

    private static Join firstJoin(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        Join[] found = new Join[1];
        script.accept(new AbstractAstVisitor<Void>() {
            @Override
            public Void visitJoin(Join node) {
                if (found[0] == null) {
                    found[0] = node;
                }
                return super.visitJoin(node);
            }
        });
        assertThat(found[0]).isNotNull();
        return found[0];
    }
}
