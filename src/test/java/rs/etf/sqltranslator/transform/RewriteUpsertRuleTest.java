package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewriteUpsertRuleTest {

    @Test
    void onConflictDoUpdateBuildsOnPostgresql() {
        var script = AstBuilderFacade.buildScript(
                "INSERT INTO t (id, name) VALUES (1, 'a') "
                        + "ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name;",
                Dialect.POSTGRESQL);
        var insert = (rs.etf.sqltranslator.ast.InsertStatement) script.statements().get(0);
        assertThat(insert.upsert()).isPresent();
        assertThat(insert.upsert().get().kind())
                .isEqualTo(rs.etf.sqltranslator.ast.UpsertKind.ON_CONFLICT_UPDATE);
    }

    @Test
    void onConflictDoUpdateRewritesToOnDuplicateTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                "INSERT INTO attendance (link_to_event, link_to_member, attend) "
                        + "VALUES ('e', 'm', 1) "
                        + "ON CONFLICT (link_to_event, link_to_member) DO UPDATE SET attend = 1;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("ON DUPLICATE KEY UPDATE");
        assertThat(sql).doesNotContainIgnoringCase("ON CONFLICT");
        assertThat(sql).containsIgnoringCase("attend");
    }

    @Test
    void onConflictDoUpdatePreservedTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "INSERT INTO t (id, v) VALUES (1, 2) ON CONFLICT (id) DO UPDATE SET v = 3;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("ON CONFLICT");
        assertThat(sql).containsIgnoringCase("DO UPDATE SET");
    }

    @Test
    void onConflictRefusedTowardTsql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "INSERT INTO t (id, v) VALUES (1, 2) ON CONFLICT (id) DO UPDATE SET v = 3;",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("T-SQL");
    }

    @Test
    void onDuplicateKeyBuildsOnMysql() {
        var script = AstBuilderFacade.buildScript(
                "INSERT INTO products (ProductID, Description) VALUES (2, 'Special') "
                        + "ON DUPLICATE KEY UPDATE Description = CONCAT(Description, ',Special');",
                Dialect.MYSQL);
        var insert = (rs.etf.sqltranslator.ast.InsertStatement) script.statements().get(0);
        assertThat(insert.upsert()).isPresent();
        assertThat(insert.upsert().get().kind())
                .isEqualTo(rs.etf.sqltranslator.ast.UpsertKind.ON_DUPLICATE_KEY);
    }

    @Test
    void onDuplicateKeyRefusedTowardPostgresql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "INSERT INTO products (ProductID, Description) VALUES (2, 'Special') "
                        + "ON DUPLICATE KEY UPDATE Description = CONCAT(Description, ',Special');",
                Dialect.MYSQL, Dialect.POSTGRESQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("conflict target");
    }

    @Test
    void returningRefusedTowardMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "INSERT INTO t (id) VALUES (1) ON CONFLICT (id) DO UPDATE SET id = 1 "
                        + "RETURNING id;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("RETURNING");
    }

    @Test
    void doNothingRefusedTowardMysql() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                "INSERT INTO t (id) VALUES (1) ON CONFLICT (id) DO NOTHING;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("DO NOTHING");
    }
}
