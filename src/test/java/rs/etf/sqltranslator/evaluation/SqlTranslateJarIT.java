package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.cli.CliExitCode;
import rs.etf.sqltranslator.core.Dialect;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failsafe IT: {@link SqlTranslateJarAdapter} shells {@code target/sqltranslate.jar}
 * (never in-process {@code Translator}).
 */
class SqlTranslateJarIT {

    private static final Path JAR = Path.of("target", "sqltranslate.jar");

    @Test
    void translatesSelectOne() throws Exception {
        assertThat(JAR)
                .as("fat jar must exist after package (run verify / package first)")
                .exists();

        Path input = Files.createTempFile("sqltranslate-select1-", ".sql");
        Files.writeString(input, "SELECT 1;\n", StandardCharsets.UTF_8);

        SqlTranslateJarAdapter adapter = new SqlTranslateJarAdapter(JAR);
        TranslateOutcome outcome = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, input, input));

        assertThat(outcome.exitCode()).isEqualTo(CliExitCode.SUCCESS);
        assertThat(outcome.outcome()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(outcome.sql()).isNotBlank();
        assertThat(outcome.system()).isEqualTo(SystemId.SQLTRANSLATE);
    }

    @Test
    void unsupportedHexIsRefusedOk() throws Exception {
        assertThat(JAR)
                .as("fat jar must exist after package (run verify / package first)")
                .exists();

        Path input = resourcePath("cases/unsupported/hex-literal/input.mysql.sql");
        SqlTranslateJarAdapter adapter = new SqlTranslateJarAdapter(JAR);
        TranslateOutcome outcome = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, input, input));

        assertThat(outcome.exitCode()).isEqualTo(CliExitCode.UNSUPPORTED);
        assertThat(outcome.outcome()).isEqualTo(OutcomeKind.REFUSED_OK);
        assertThat(outcome.sql()).isEmpty();
        assertThat(outcome.stderr()).startsWith("error: unsupported:");
    }

    private static Path resourcePath(String classpath) throws URISyntaxException {
        var url = SqlTranslateJarIT.class.getClassLoader().getResource(classpath);
        assertThat(url).as("classpath resource %s", classpath).isNotNull();
        return Paths.get(url.toURI());
    }
}
