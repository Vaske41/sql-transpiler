package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MysqlDeleteJoinTest {

    private static final String MYSQL_DELETE_JOIN =
            "DELETE e FROM Examination e JOIN Patient p ON p.ID = e.ID WHERE p.SEX = 'F';";

    @Test
    void mysqlDeleteJoinParses() {
        assertThatCode(() -> AstBuilderFacade.buildScript(MYSQL_DELETE_JOIN, Dialect.MYSQL))
                .doesNotThrowAnyException();
    }

    @Test
    void mysqlDeleteJoinTowardPostgresqlUsesUsing() {
        String sql = CodegenTestSupport.printTranslated(
                MYSQL_DELETE_JOIN, Dialect.MYSQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("DELETE FROM");
        assertThat(sql).containsIgnoringCase("USING");
        assertThat(sql).containsIgnoringCase("WHERE");
    }

    @Test
    void mysqlDeleteJoinTowardTsqlIsRefused() {
        assertThatThrownBy(() -> CodegenTestSupport.printTranslated(
                MYSQL_DELETE_JOIN, Dialect.MYSQL, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class);
    }
}
