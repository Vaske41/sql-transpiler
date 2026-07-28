package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class RegexOperatorTest {

    @Test
    void pgRegexOperatorBecomesMysqlRegexpLikeCaseSensitive() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE name ~ '^a'", Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("REGEXP_LIKE");
        assertThat(sql).contains("'c'");
    }

    @Test
    void caseInsensitiveRegexKeepsItsFlag() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE name ~* '^a'", Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql).containsIgnoringCase("REGEXP_LIKE");
        assertThat(sql).contains("'i'");
    }
}
