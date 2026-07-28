package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Statement-terminal T-SQL OPTION slot: recursive CTEs (and later generate_series /
 * WITH RECURSIVE paths) must request MAXRECURSION once and flush a single
 * {@code OPTION (MAXRECURSION 0)} on the outermost statement only.
 */
class TsqlStatementOptionTest {

    @Test
    void recursiveCteEmitsExactlyOneMaxRecursionOption() {
        String sql = "WITH RECURSIVE c AS (SELECT 1 AS x UNION ALL SELECT x + 1 FROM c WHERE x < 10)"
                + " SELECT * FROM c;";
        String out = Translator.translate(sql, Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(out).endsWith("OPTION (MAXRECURSION 0);\n");
        assertThat(countOccurrences(out, "OPTION (MAXRECURSION 0)")).isEqualTo(1);
        assertThat(out).doesNotContain("RECURSIVE");
    }

    @Test
    void nonRecursiveQueryEmitsNoOption() {
        String out = Translator.translate("SELECT 1 AS x;", Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(out).isEqualTo("SELECT 1 AS x;\n");
        assertThat(out).doesNotContain("OPTION");
    }

    @Test
    void doubleRequireStillEmitsExactlyOneOption() {
        // Stand-in for Task 7 (generate_series) + Task 20 both calling requireMaxRecursion
        // before flush — must not emit two OPTION clauses. TSqlPrinter is final, so this
        // uses the package-visible helper rather than subclassing.
        assertThat(TSqlPrinter.optionsAfterRequires(2)).isEqualTo("OPTION (MAXRECURSION 0)");
        assertThat(TSqlPrinter.optionsAfterRequires(0)).isEmpty();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = 0; (i = haystack.indexOf(needle, i)) >= 0; i += needle.length()) {
            count++;
        }
        return count;
    }
}
