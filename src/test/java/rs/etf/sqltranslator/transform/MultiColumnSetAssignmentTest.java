package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rs.etf.sqltranslator.transform.TransformTestSupport.runRule;

class MultiColumnSetAssignmentTest {

    private final Rule rule = new ValidateTargetCapabilitiesRule();

    @Test
    void postgresqlMultiColumnSetParses() {
        UpdateStatement upd = (UpdateStatement) AstBuilderFacade.buildScript(
                "UPDATE t SET (a, b) = (1, 2) WHERE id = 3;",
                Dialect.POSTGRESQL).statements().get(0);
        Assignment assignment = upd.assignments().get(0);
        assertThat(assignment.columns()).hasSize(2);
        assertThat(assignment.columns().get(0).last().value()).isEqualToIgnoringCase("a");
        assertThat(assignment.columns().get(1).last().value()).isEqualToIgnoringCase("b");
    }

    @Test
    void postgresqlMultiColumnSetPrintsTowardPostgresql() {
        String sql = """
                UPDATE t SET (a, b) = (SELECT x, y FROM s WHERE s.id = t.id)
                WHERE t.id = 1;
                """;
        String out = CodegenTestSupport.printTranslated(
                sql, Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(out).contains("(a, b)");
        assertThat(out).contains("SET (");
        assertThat(out).containsIgnoringCase("SELECT");
    }

    @Test
    void multiColumnSetTowardMysqlIsRefused() {
        assertThatThrownBy(() -> runRule(rule,
                "UPDATE t SET (a, b) = (1, 2) WHERE id = 3;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("multi-column SET assignment");
    }

    @Test
    void singleColumnSetTowardMysqlIsFine() {
        assertThatCode(() -> runRule(rule,
                "UPDATE t SET a = 1 WHERE id = 3;",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .doesNotThrowAnyException();
    }
}
