package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class SetOpNestingTest {

    @Test
    void parenthesizedSetOperandsParse() {
        assertThat(CodegenTestSupport.printTranslated(
                "(SELECT a FROM t) UNION (SELECT b FROM u) ORDER BY 1",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .containsIgnoringCase("UNION");
    }

    @Test
    void recursiveCteCrossesToTsqlWithMaxrecursion() {
        assertThat(CodegenTestSupport.printTranslated(
                "WITH RECURSIVE c(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM c WHERE n<5) "
                        + "SELECT n FROM c", Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .containsIgnoringCase("OPTION (MAXRECURSION 0)")
                .doesNotContainIgnoringCase("RECURSIVE");
    }
}
