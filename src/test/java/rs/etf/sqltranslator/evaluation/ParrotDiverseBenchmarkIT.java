package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failsafe: Java-written PARROT-Diverse smoke under
 * {@code target/evaluation/parrot-diverse-smoke/} (no Python in CI);
 * {@link BenchmarkDriver#parrotDiverseOffline} → {@code parrot-diverse-*.csv}.
 */
class ParrotDiverseBenchmarkIT {

    private static final Path JAR = Path.of("target", "sqltranslate.jar");
    private static final Path CSV =
            Path.of("target", "evaluation", "summary", "parrot-diverse-smoke.csv");
    private static final Path SMOKE_ROOT =
            Path.of("target", "evaluation", "parrot-diverse-smoke");

    private static final Set<String> PHASE7_OUTCOMES = Set.of(
            OutcomeKind.SUCCESS.name(),
            OutcomeKind.REFUSED_OK.name(),
            OutcomeKind.REFUSED.name(),
            OutcomeKind.WRONG_INVENTION.name(),
            OutcomeKind.PARSE.name(),
            OutcomeKind.INTERNAL.name(),
            OutcomeKind.NO_FIXTURE.name(),
            OutcomeKind.ERROR.name());

    @Test
    void parrotDiverseOfflineWritesCsvWithSqltranslateOutcome() throws Exception {
        assertThat(JAR)
                .as("fat jar must exist after package (run verify / package first)")
                .exists();

        writeSmokeCase(
                "00000-smoke-mysql-to-postgresql",
                "mysql",
                "postgresql",
                "SELECT 1 AS n");
        writeSmokeCase(
                "00001-smoke-postgresql-to-mysql",
                "postgresql",
                "mysql",
                "SELECT 1 AS n");
        writeSmokeCase(
                "00002-smoke-tsql-to-mysql",
                "tsql",
                "mysql",
                "SELECT 1 AS n");

        Files.deleteIfExists(CSV);
        BenchmarkDriver driver =
                BenchmarkDriver.parrotDiverseOffline(JAR, CSV, SMOKE_ROOT, false, 0);
        var rows = driver.run();

        assertThat(CSV).exists();
        String csv = Files.readString(CSV, StandardCharsets.UTF_8);
        assertThat(csv).startsWith(CsvResultsWriter.HEADER);
        assertThat(csv).isNotBlank();
        assertThat(csv).contains("sqltranslate");

        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().map(ScoreRow::system)).contains("sqltranslate");
        assertThat(rows.stream()
                        .filter(r -> r.system().equals("sqltranslate"))
                        .map(ScoreRow::outcome)
                        .filter(PHASE7_OUTCOMES::contains)
                        .findAny())
                .as("at least one sqltranslate row with a Phase 7 outcome")
                .isPresent();
    }

    private static void writeSmokeCase(
            String caseId, String source, String target, String sql) throws Exception {
        Path caseDir = SMOKE_ROOT.resolve(caseId);
        Files.createDirectories(caseDir);
        Files.writeString(
                caseDir.resolve("input." + source + ".sql"), sql, StandardCharsets.UTF_8);
        Files.writeString(caseDir.resolve("target.txt"), target + "\n", StandardCharsets.UTF_8);
    }
}
