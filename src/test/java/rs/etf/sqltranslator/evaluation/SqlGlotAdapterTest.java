package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Surefire unit test for {@link SqlGlotAdapter}. Skips when Python/sqlglot is missing.
 */
class SqlGlotAdapterTest {

    @Test
    @EnabledIf("rs.etf.sqltranslator.evaluation.SqlGlotAdapter#available")
    void selectOneMysqlToPostgresql() throws Exception {
        Path input = Files.createTempFile("sqlglot-select1-", ".sql");
        Files.writeString(input, "SELECT 1;\n", StandardCharsets.UTF_8);

        SqlGlotAdapter adapter = new SqlGlotAdapter();
        TranslateOutcome outcome = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, input, input));

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.outcome()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(outcome.system()).isEqualTo(SystemId.SQLGLOT);
        assertThat(outcome.sql()).containsIgnoringCase("SELECT");
        assertThat(outcome.sql()).contains("1");
    }
}
