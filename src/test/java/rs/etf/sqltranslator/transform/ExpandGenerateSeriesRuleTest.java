package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;

import static org.assertj.core.api.Assertions.assertThat;

class ExpandGenerateSeriesRuleTest {

    @Test
    void generateSeriesBecomesRecursiveCteForTsql() {
        String out = Translator.translate(
                "SELECT g FROM generate_series(1, 10) AS g",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(out).containsIgnoringCase("WITH")
                .containsIgnoringCase("UNION ALL")
                .containsIgnoringCase("OPTION (MAXRECURSION 0)");
    }

    @Test
    void generateSeriesIsLeftAloneForPostgres() {
        assertThat(Translator.translate(
                "SELECT g FROM generate_series(1, 10) AS g",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql())
                .containsIgnoringCase("generate_series");
    }
}
