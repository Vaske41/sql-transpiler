package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.etf.sqltranslator.core.Dialect;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeAdapterTest {

    @TempDir
    Path temp;

    @Test
    void missingFixtureYieldsNoFixture() throws Exception {
        FixtureStore store = new FixtureStore(temp);
        PromptTemplate prompt = new PromptTemplate(
                "Translate the following {src} SQL to {tgt}. Output only SQL.\n\n{sql}\n");
        ClaudeAdapter adapter = new ClaudeAdapter(store, prompt, HttpClient.newHttpClient(), true);

        Path casePath = temp.resolve("cases").resolve("missing").resolve("case");
        Files.createDirectories(casePath);
        Path input = casePath.resolve("input.mysql.sql");
        Files.writeString(input, "SELECT 1;\n", StandardCharsets.UTF_8);

        TranslateOutcome out = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, casePath, input));

        assertThat(out.outcome()).isEqualTo(OutcomeKind.NO_FIXTURE);
        assertThat(out.status()).isEqualTo("NO_FIXTURE");
        assertThat(out.system()).isEqualTo(SystemId.CLAUDE);
    }

    @Test
    void fixtureHitReturnsSuccessWithoutNetwork() throws Exception {
        ClaudeAdapter adapter = new ClaudeAdapter(
                new FixtureStore(),
                PromptTemplate.load(),
                HttpClient.newHttpClient(),
                true);

        Path casePath = Path.of("src", "test", "resources", "cases", "select-basic", "select-literal");
        Path input = casePath.resolve("input.mysql.sql");

        TranslateOutcome out = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, casePath, input));

        assertThat(out.outcome()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(out.sql().trim()).isEqualTo("SELECT 1;");
        assertThat(out.system()).isEqualTo(SystemId.CLAUDE);
    }

    @Test
    void stripsMarkdownFencesFromFixture() throws Exception {
        FixtureStore store = new FixtureStore(temp);
        store.write(
                SystemId.CLAUDE,
                "fenced/case",
                Dialect.MYSQL,
                Dialect.POSTGRESQL,
                "```\nSELECT 3;\n```\n",
                ClaudeAdapter.MODEL,
                PromptTemplate.VERSION,
                "2026-07-13T00:00:00Z",
                1L);

        Path casePath = Path.of("src", "test", "resources", "cases", "fenced", "case");
        Path input = Files.createTempFile(temp, "in-", ".sql");
        Files.writeString(input, "SELECT 3;\n", StandardCharsets.UTF_8);

        ClaudeAdapter adapter = new ClaudeAdapter(
                store, PromptTemplate.load(), HttpClient.newHttpClient(), true);
        TranslateOutcome out = adapter.translate(new TranslateRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, casePath, input));

        assertThat(out.outcome()).isEqualTo(OutcomeKind.SUCCESS);
        assertThat(out.sql().trim()).isEqualTo("SELECT 3;");
    }
}
