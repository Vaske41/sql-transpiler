package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Composer 2.5 baseline via Cursor SDK helper {@code evaluation/bin/composer_transpile.py}.
 * Fixture-first; live only with {@code eval.live}/{@code EVAL_LIVE} and {@code CURSOR_API_KEY}.
 */
final class ComposerAdapter implements TranslatorAdapter {

    static final String MODEL = "composer-2.5";
    /** Pinned version matching {@code evaluation/bin/requirements.txt}. */
    static final String CURSOR_SDK_VERSION = "0.1.9";
    static final long TIMEOUT_SECONDS = 300L;
    static final Path HELPER = Path.of("evaluation", "bin", "composer_transpile.py");
    static final Path PROMPT_PATH = Path.of("evaluation", "prompts", "v1.txt");
    private static final String API_KEY_ENV = "CURSOR_API_KEY";

    private final FixtureStore store;
    private final Path helper;
    private final Path promptPath;
    private final String python;
    private final boolean forceOffline;

    ComposerAdapter() {
        this(
                new FixtureStore(),
                HELPER,
                PROMPT_PATH,
                SqlGlotAdapter.resolvePython().orElse("python"),
                false);
    }

    ComposerAdapter(FixtureStore store, boolean forceOffline) {
        this(
                store,
                HELPER,
                PROMPT_PATH,
                SqlGlotAdapter.resolvePython().orElse("python"),
                forceOffline);
    }

    ComposerAdapter(
            FixtureStore store,
            Path helper,
            Path promptPath,
            String python,
            boolean forceOffline) {
        this.store = Objects.requireNonNull(store, "store");
        this.helper = Objects.requireNonNull(helper, "helper");
        this.promptPath = Objects.requireNonNull(promptPath, "promptPath");
        this.python = Objects.requireNonNull(python, "python");
        this.forceOffline = forceOffline;
    }

    @Override
    public SystemId systemId() {
        return SystemId.COMPOSER;
    }

    @Override
    public TranslateOutcome translate(TranslateRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        String caseKey = FixtureStore.caseKeyFrom(request.casePath());
        Dialect source = request.source();
        Dialect target = request.target();

        if (!forceOffline && LlmText.liveEnabled()) {
            String key = System.getenv(API_KEY_ENV);
            if (key != null && !key.isBlank()) {
                return liveTranslate(request, caseKey);
            }
        }

        Optional<String> fixture = store.readSql(SystemId.COMPOSER, caseKey, source, target);
        if (fixture.isEmpty()) {
            return noFixture();
        }
        String sql = LlmText.stripMarkdownFences(fixture.get());
        long latency = GeminiAdapter.parseLatencyMs(
                store.readMeta(SystemId.COMPOSER, caseKey, source, target).orElse(""));
        return new TranslateOutcome(
                SystemId.COMPOSER, OutcomeKind.SUCCESS, 0, sql, "", latency, OutcomeKind.SUCCESS.name());
    }

    private TranslateOutcome liveTranslate(TranslateRequest request, String caseKey)
            throws Exception {
        byte[] sqlBytes = Files.readAllBytes(request.inputFile());
        String promptAbs = promptPath.toAbsolutePath().normalize().toString();
        String helperAbs = helper.toAbsolutePath().normalize().toString();
        ProcessRunner.Result run = ProcessRunner.run(
                List.of(
                        python,
                        helperAbs,
                        "--from", cliName(request.source()),
                        "--to", cliName(request.target()),
                        "--prompt", promptAbs),
                sqlBytes,
                TIMEOUT_SECONDS);
        if (run.exitCode() != 0) {
            return new TranslateOutcome(
                    SystemId.COMPOSER,
                    OutcomeKind.ERROR,
                    run.exitCode(),
                    run.stdoutUtf8(),
                    run.stderrUtf8(),
                    run.latencyMs(),
                    OutcomeKind.ERROR.name());
        }
        String sql = LlmText.stripMarkdownFences(run.stdoutUtf8());
        store.write(
                SystemId.COMPOSER,
                caseKey,
                request.source(),
                request.target(),
                sql,
                MODEL,
                PromptTemplate.VERSION,
                Instant.now().toString(),
                run.latencyMs(),
                Map.of("cursorSdk", CURSOR_SDK_VERSION));
        return new TranslateOutcome(
                SystemId.COMPOSER,
                OutcomeKind.SUCCESS,
                run.exitCode(),
                sql,
                run.stderrUtf8(),
                run.latencyMs(),
                OutcomeKind.SUCCESS.name());
    }

    private static TranslateOutcome noFixture() {
        return new TranslateOutcome(
                SystemId.COMPOSER, OutcomeKind.NO_FIXTURE, -1, "", "missing fixture", 0L,
                OutcomeKind.NO_FIXTURE.name());
    }

    private static String cliName(Dialect dialect) {
        return dialect.name().toLowerCase(Locale.ROOT);
    }
}
