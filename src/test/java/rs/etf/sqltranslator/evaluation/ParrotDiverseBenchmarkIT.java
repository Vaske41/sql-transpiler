package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failsafe: Java-written PARROT-Diverse smoke under
 * {@code target/evaluation/parrot-diverse-smoke/} (no Python in CI);
 * {@link BenchmarkDriver#parrotDiverseOffline} → {@code parrot-diverse-*.csv}.
 *
 * <p>Gates the parrot path: smoke {@code case_id}s, sqltranslate {@code SUCCESS},
 * and {@code notes} containing {@code corpus=parrot-diverse}.
 */
class ParrotDiverseBenchmarkIT {

    private static final Path JAR = Path.of("target", "sqltranslate.jar");
    private static final Path CSV =
            Path.of("target", "evaluation", "summary", "parrot-diverse-smoke.csv");
    private static final Path SMOKE_ROOT =
            Path.of("target", "evaluation", "parrot-diverse-smoke");

    private static final List<String> SMOKE_CASE_IDS = List.of(
            "00000-smoke-mysql-to-postgresql",
            "00001-smoke-postgresql-to-mysql",
            "00002-smoke-tsql-to-mysql");

    @Test
    void parrotDiverseOfflineWritesCsvWithSqltranslateSuccessOnSmokeCases() throws Exception {
        assertThat(JAR)
                .as("fat jar must exist after package (run verify / package first)")
                .exists();

        wipeDirectory(SMOKE_ROOT);
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
        assertThat(csv).contains("sqltranslate");

        assertThat(rows).isNotEmpty();
        for (String caseId : SMOKE_CASE_IDS) {
            assertThat(rows.stream().map(ScoreRow::caseId))
                    .as("CSV must include parrot smoke case_id %s (proves parrotCasesRoot)", caseId)
                    .contains(caseId);
        }

        List<ScoreRow> sqltranslateSmoke = rows.stream()
                .filter(r -> "sqltranslate".equals(r.system()))
                .filter(r -> SMOKE_CASE_IDS.contains(r.caseId()))
                .toList();
        assertThat(sqltranslateSmoke)
                .as("sqltranslate rows for all three smoke cases")
                .hasSize(SMOKE_CASE_IDS.size());
        assertThat(sqltranslateSmoke)
                .as("SELECT 1 smoke must SUCCESS on sqltranslate")
                .allMatch(r -> OutcomeKind.SUCCESS.name().equals(r.outcome()));
        assertThat(sqltranslateSmoke)
                .as("notes must stamp parrot-diverse corpus")
                .allMatch(r -> r.notes() != null && r.notes().contains("corpus=parrot-diverse"));
    }

    private static void wipeDirectory(Path root) throws Exception {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
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
