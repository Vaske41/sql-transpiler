package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class RegexOperatorTest {

    @Test
    void pgRegexOperatorBecomesMysqlRegexp() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE name ~ '^a'", Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .containsIgnoringCase("REGEXP");
    }

    @Test
    void caseInsensitiveRegexKeepsItsFlag() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE name ~* '^a'", Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .containsIgnoringCase("REGEXP_LIKE");
    }
}
