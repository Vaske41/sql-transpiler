package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Surefire unit coverage for scoring / CSV helpers (no fat jar required).
 * Jar-backed smoke lives in {@link BenchmarkDriverOfflineIT} (Failsafe).
 */
class BenchmarkDriverOfflineTest {

    @Test
    void outcomeScorerMarksUnsupportedLlmInvention() {
        Path unsupported = Path.of("src", "test", "resources", "cases", "unsupported", "hex-literal");
        TranslateOutcome invented = new TranslateOutcome(
                SystemId.GEMINI, OutcomeKind.SUCCESS, 0, "SELECT 1;", "", 1L, "SUCCESS");
        assertThat(OutcomeScorer.score(SystemId.GEMINI, unsupported, invented))
                .isEqualTo(OutcomeKind.WRONG_INVENTION);

        TranslateOutcome empty = new TranslateOutcome(
                SystemId.CLAUDE, OutcomeKind.SUCCESS, 0, "", "", 1L, "SUCCESS");
        assertThat(OutcomeScorer.score(SystemId.CLAUDE, unsupported, empty))
                .isEqualTo(OutcomeKind.REFUSED);

        Path normal = Path.of("src", "test", "resources", "cases", "select-basic", "select-literal");
        assertThat(OutcomeScorer.score(SystemId.GEMINI, normal, invented))
                .isEqualTo(OutcomeKind.SUCCESS);
    }

    @Test
    void sqltranslateClassifiesRefusedOkAndWrongInvention() {
        Path unsupported = Path.of("cases", "unsupported", "hex-literal");
        assertThat(SqlTranslateJarAdapter.classifySqlTranslate(2, unsupported))
                .isEqualTo(OutcomeKind.REFUSED_OK);
        assertThat(SqlTranslateJarAdapter.classifySqlTranslate(0, unsupported))
                .isEqualTo(OutcomeKind.WRONG_INVENTION);
        Path normal = Path.of("cases", "select-basic", "select-literal");
        assertThat(SqlTranslateJarAdapter.classifySqlTranslate(2, normal))
                .isEqualTo(OutcomeKind.REFUSED);
        assertThat(SqlTranslateJarAdapter.classifySqlTranslate(0, normal))
                .isEqualTo(OutcomeKind.SUCCESS);
    }

    @Test
    void csvWriterEmitsOutcomeColumn() throws Exception {
        Path csv = Path.of("target", "evaluation", "summary", "unit-smoke.csv");
        CsvResultsWriter.write(csv, java.util.List.of(
                new ScoreRow(
                        "sqltranslate",
                        "select-basic/select-literal/input.mysql.sql",
                        "mysql",
                        "postgresql",
                        "SUCCESS",
                        "0",
                        "n/a",
                        "n/a",
                        "true",
                        "12",
                        "test")));
        String text = java.nio.file.Files.readString(csv);
        assertThat(text).startsWith(CsvResultsWriter.HEADER);
        assertThat(text).contains("sqltranslate");
        assertThat(CsvResultsWriter.HEADER).contains("outcome");
    }

    @Test
    void singleStatementHeuristic() {
        assertThat(OutcomeScorer.isSingleStatement("SELECT 1;")).isTrue();
        assertThat(OutcomeScorer.isSingleStatement("SELECT 1;\nSELECT 2;")).isFalse();
        assertThat(OutcomeScorer.isSingleStatement("")).isTrue();
    }
}
