package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failsafe: limited corpus via fat jar + LLM fixtures; writes
 * {@code target/evaluation/summary/latest.csv}.
 */
class BenchmarkDriverOfflineIT {

    private static final Path JAR = Path.of("target", "sqltranslate.jar");
    private static final Path CSV = Path.of("target", "evaluation", "summary", "latest.csv");

    @Test
    void offlineDriverWritesCsvWithSqltranslateAndOutcome() throws Exception {
        assertThat(JAR)
                .as("fat jar must exist after package (run verify / package first)")
                .exists();

        Files.deleteIfExists(CSV);
        BenchmarkDriver driver = BenchmarkDriver.offlineLimited(JAR, CSV);
        var rows = driver.run();

        assertThat(CSV).exists();
        String csv = Files.readString(CSV, StandardCharsets.UTF_8);
        assertThat(csv).startsWith(CsvResultsWriter.HEADER);
        assertThat(csv).contains("sqltranslate");
        assertThat(CsvResultsWriter.HEADER).contains("outcome");
        assertThat(csv).contains("SUCCESS");
        assertThat(csv).contains("REFUSED_OK");

        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().map(ScoreRow::system)).contains("sqltranslate");
        assertThat(rows.stream().map(ScoreRow::outcome))
                .contains(OutcomeKind.SUCCESS.name(), OutcomeKind.REFUSED_OK.name());

        assertThat(rows.stream()
                .filter(r -> r.system().equals("gemini")
                        && r.caseId().contains("select-literal")
                        && r.source().equals("mysql")
                        && r.target().equals("postgresql"))
                .map(ScoreRow::outcome))
                .containsExactly(OutcomeKind.SUCCESS.name());
    }
}
