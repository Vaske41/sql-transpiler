package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.DeleteStatement;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeleteUsingTest {

    private static final String DELETE_USING =
            "DELETE FROM orders AS o USING customers AS c WHERE o.customer_id = c.id;";

    @Test
    void postgresqlDeleteUsingParsesWithAliasAndUsingClause() {
        var script = AstBuilderFacade.buildScript(DELETE_USING, Dialect.POSTGRESQL);
        var delete = (DeleteStatement) script.statements().get(0);
        assertThat(delete.alias()).isPresent();
        assertThat(delete.alias().orElseThrow().value()).isEqualTo("o");
        assertThat(delete.usingClause()).isPresent();
        assertThat(delete.where()).isPresent();
    }

    @Test
    void postgresqlDeleteUsingPrintsTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                DELETE_USING, Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("DELETE FROM orders");
        assertThat(sql).containsIgnoringCase("AS o");
        assertThat(sql).containsIgnoringCase("USING");
        assertThat(sql).containsIgnoringCase("customers");
        assertThat(sql).containsIgnoringCase("WHERE");
    }

    @Test
    void postgresqlDeleteUsingPrintsTowardMysql() {
        String sql = CodegenTestSupport.printTranslated(
                DELETE_USING, Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("DELETE FROM orders");
        assertThat(sql).containsIgnoringCase("AS o");
        assertThat(sql).containsIgnoringCase("USING");
        assertThat(sql).containsIgnoringCase("customers");
        assertThat(sql).containsIgnoringCase("WHERE");
    }

    @Test
    void postgresqlDeleteUsingTowardTsqlIsRefused() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                DELETE_USING, Dialect.POSTGRESQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("DELETE USING");
    }

    @Test
    void plainDeleteStillWorks() {
        assertThatCode(() -> CodegenTestSupport.printTranslated(
                "DELETE FROM sessions WHERE expires_at < '2026-01-01';",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .doesNotThrowAnyException();
    }
}
