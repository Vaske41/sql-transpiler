package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class WithUpdateTest {

    private static final String WITH_UPDATE = """
            WITH T0 AS (SELECT 1 AS id)
            UPDATE t SET x = 1 FROM T0 WHERE t.id = T0.id;
            """;

    @Test
    void withUpdateParsesOnPostgresql() {
        var script = AstBuilderFacade.buildScript(WITH_UPDATE, Dialect.POSTGRESQL);
        var update = (UpdateStatement) script.statements().get(0);
        assertThat(update.ctes()).hasSize(1);
        assertThat(update.ctes().get(0).name().value()).isEqualToIgnoringCase("T0");
    }

    @Test
    void withUpdatePrintsTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                WITH_UPDATE, Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("WITH");
        assertThat(sql).containsIgnoringCase("UPDATE");
        assertThat(sql).containsIgnoringCase("FROM");
        assertThat(sql).containsIgnoringCase("WHERE");
    }

    @Test
    void withUpdatePrintsTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                WITH_UPDATE, Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("WITH");
        assertThat(sql).containsIgnoringCase("UPDATE");
    }

    @Test
    void withUpdatePrintsTowardTsql() {
        String sql = CodegenTestSupport.printTranslated(
                WITH_UPDATE, Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql).containsIgnoringCase("WITH");
        assertThat(sql).containsIgnoringCase("UPDATE");
    }

    @Test
    void plainUpdateStillWorks() {
        assertThatCode(() -> CodegenTestSupport.printTranslated(
                "UPDATE t SET x = 1 WHERE id = 2;",
                Dialect.MYSQL, Dialect.POSTGRESQL))
                .doesNotThrowAnyException();
    }
}
