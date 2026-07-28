package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rs.etf.sqltranslator.codegen.CodegenTestSupport.printTranslated;

class CreateIndexAdvancedTest {

    @Test
    void expressionKeyParsesAndPrints() {
        assertThat(printTranslated(
                "CREATE INDEX idx ON t (CASE WHEN a IS NULL THEN 1 ELSE 0 END, a)",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql())
                .containsIgnoringCase("CASE");
    }

    @Test
    void partialIndexReachesTsqlAsFilteredIndex() {
        assertThat(printTranslated(
                "CREATE UNIQUE INDEX u ON e (name) WHERE active",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .containsIgnoringCase("WHERE");
    }

    @Test
    void partialIndexStillRefusesForMysql() {
        assertThatThrownBy(() -> printTranslated(
                "CREATE UNIQUE INDEX u ON e (name) WHERE active",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("partial index");
    }

    @Test
    void expressionKeyRefusesForTsql() {
        assertThatThrownBy(() -> printTranslated(
                "CREATE INDEX idx ON t (CASE WHEN a IS NULL THEN 1 ELSE 0 END, a)",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("expression index");
    }

    @Test
    void includeColumnsRefuseForMysql() {
        assertThatThrownBy(() -> printTranslated(
                "CREATE INDEX idx ON t (a) INCLUDE (event_id)",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("INCLUDE");
    }
}
