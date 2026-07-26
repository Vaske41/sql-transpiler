package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ParenthesizedSelectTest {

    @Test
    void parenthesizedSelectParsesOnMysql() {
        assertThatCode(() -> AstBuilderFacade.buildScript(
                "(SELECT 1 AS n);",
                Dialect.MYSQL))
                .doesNotThrowAnyException();
    }

    @Test
    void parenthesizedSelectTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "(SELECT id FROM t WHERE x = 1);",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("SELECT");
        assertThat(sql).containsIgnoringCase("FROM");
        assertThat(sql).containsIgnoringCase("WHERE");
    }
}
