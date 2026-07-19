package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI helpers for {@link EvaluationMain} (corpus branding + budget warn).
 */
class EvaluationMainTest {

    @Test
    void parseCorpusDefaultsToGolden() {
        assertThat(EvaluationMain.parseCorpus(null)).isEqualTo("golden");
        assertThat(EvaluationMain.parseCorpus(new String[] {})).isEqualTo("golden");
        assertThat(EvaluationMain.parseCorpus(new String[] {"--sqlglot"})).isEqualTo("golden");
    }

    @Test
    void parseCorpusAcceptsGoldenAndParrotDiverse() {
        assertThat(EvaluationMain.parseCorpus(new String[] {"--corpus", "golden"}))
                .isEqualTo("golden");
        assertThat(EvaluationMain.parseCorpus(new String[] {"--corpus", "parrot-diverse"}))
                .isEqualTo("parrot-diverse");
    }

    @Test
    void warnIfOverCommittedBudgetPrintsOnParrotDiverseOverLimit() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            EvaluationMain.warnIfOverCommittedBudget(
                    "gemini", "parrot-diverse", 21, EvaluationMain.COMMITTED_GEMINI_BUDGET);
        } finally {
            System.setErr(previous);
        }
        assertThat(buf.toString(StandardCharsets.UTF_8))
                .contains("local PARROT-Diverse gemini fixture budget")
                .contains("≤20");
    }

    @Test
    void warnIfOverCommittedBudgetSilentForGolden() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            EvaluationMain.warnIfOverCommittedBudget("gemini", "golden", 100, 20);
        } finally {
            System.setErr(previous);
        }
        assertThat(buf.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
