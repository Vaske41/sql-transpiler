package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ComposerAdapterTest {

    @TempDir
    Path temp;

    @Test
    void missingFixtureYieldsNoFixture() throws Exception {
        FixtureStore store = new FixtureStore(temp);
        ComposerAdapter adapter = new ComposerAdapter(
                store, ComposerAdapter.HELPER, ComposerAdapter.PROMPT_PATH, "python", true);

        Path casePath = temp.resolve("cases").resolve("missing").resolve("case");
        Files.createDirectories(casePath);
        Path input = casePath.resolve("input.mysql.sql");
        Files.writeString(input, "SELECT 1;\n", StandardCharsets.UTF_8);

        TranslateOutcome out = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, casePath, input));

        assertThat(out.outcome()).isEqualTo(OutcomeKind.NO_FIXTURE);
        assertThat(out.status()).isEqualTo("NO_FIXTURE");
        assertThat(out.system()).isEqualTo(SystemId.COMPOSER);
    }

    @Test
    void fixtureHitReturnsSuccessWithoutNetwork() throws Exception {
        ComposerAdapter adapter = new ComposerAdapter(new FixtureStore(), true);

        Path casePath = Path.of("src", "test", "resources", "cases", "select-basic", "select-literal");
        Path input = casePath.resolve("input.mysql.sql");

        TranslateOutcome out = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, casePath, input));

        assertThat(out.outcome()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(out.sql().trim()).isEqualTo("SELECT 1;");
        assertThat(out.system()).isEqualTo(SystemId.COMPOSER);
    }

    @Test
    void stripsMarkdownFencesFromFixture() throws Exception {
        FixtureStore store = new FixtureStore(temp);
        store.write(
                SystemId.COMPOSER,
                "fenced/case",
                Dialect.MYSQL,
                Dialect.POSTGRESQL,
                "```\nSELECT 3;\n```\n",
                ComposerAdapter.MODEL,
                PromptTemplate.VERSION,
                "2026-07-14T00:00:00Z",
                1L);

        Path casePath = Path.of("src", "test", "resources", "cases", "fenced", "case");
        Path input = Files.createTempFile(temp, "in-", ".sql");
        Files.writeString(input, "SELECT 3;\n", StandardCharsets.UTF_8);

        ComposerAdapter adapter = new ComposerAdapter(
                store, ComposerAdapter.HELPER, ComposerAdapter.PROMPT_PATH, "python", true);
        TranslateOutcome out = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, casePath, input));

        assertThat(out.outcome()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(out.sql().trim()).isEqualTo("SELECT 3;");
    }

    @Test
    void processRunnerTimeoutDestroysAndNotesTimeout() throws Exception {
        String python = SqlGlotAdapter.resolvePython().orElse("python");
        long start = System.currentTimeMillis();
        ProcessRunner.Result result = ProcessRunner.run(
                java.util.List.of(python, "-c", "import time; time.sleep(30)"),
                null,
                1);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.stderrUtf8().toLowerCase()).contains("timeout");
        assertThat(elapsed).isLessThan(15_000L);
    }

    @Test
    void pinnedCursorSdkMatchesRequirements() throws Exception {
        String requirements = Files.readString(Path.of("evaluation", "bin", "requirements.txt"));
        assertThat(ComposerAdapter.CURSOR_SDK_VERSION).isEqualTo("0.1.9");
        assertThat(requirements).contains("cursor-sdk==" + ComposerAdapter.CURSOR_SDK_VERSION);
        assertThat(ComposerAdapter.TIMEOUT_SECONDS).isEqualTo(300L);
    }
}
