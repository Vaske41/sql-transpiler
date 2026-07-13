package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.core.Dialect;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Claude Sonnet baseline ({@code claude-sonnet-4-6}, temperature 0). Fixture-first; live only with
 * {@code eval.live}/{@code EVAL_LIVE} and {@code ANTHROPIC_API_KEY}.
 */
final class ClaudeAdapter implements TranslatorAdapter {

    static final String MODEL = "claude-sonnet-4-6";
    private static final String API_KEY_ENV = "ANTHROPIC_API_KEY";

    private final FixtureStore store;
    private final PromptTemplate prompt;
    private final HttpClient http;
    private final boolean forceOffline;

    ClaudeAdapter() throws Exception {
        this(new FixtureStore(), PromptTemplate.load(), HttpClient.newHttpClient(), false);
    }

    ClaudeAdapter(FixtureStore store, PromptTemplate prompt, HttpClient http, boolean forceOffline) {
        this.store = Objects.requireNonNull(store, "store");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.http = Objects.requireNonNull(http, "http");
        this.forceOffline = forceOffline;
    }

    @Override
    public SystemId systemId() {
        return SystemId.CLAUDE;
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
                return liveTranslate(request, caseKey, key);
            }
        }

        Optional<String> fixture = store.readSql(SystemId.CLAUDE, caseKey, source, target);
        if (fixture.isEmpty()) {
            return noFixture();
        }
        String sql = LlmText.stripMarkdownFences(fixture.get());
        long latency = GeminiAdapter.parseLatencyMs(
                store.readMeta(SystemId.CLAUDE, caseKey, source, target).orElse(""));
        return new TranslateOutcome(
                SystemId.CLAUDE, OutcomeKind.SUCCESS, 0, sql, "", latency, OutcomeKind.SUCCESS.name());
    }

    private TranslateOutcome liveTranslate(TranslateRequest request, String caseKey, String apiKey)
            throws Exception {
        String inputSql = Files.readString(request.inputFile(), StandardCharsets.UTF_8);
        String rendered = prompt.render(request.source(), request.target(), inputSql);
        String body = """
                {
                  "model": %s,
                  "max_tokens": 4096,
                  "temperature": 0,
                  "messages": [{"role":"user","content":%s}]
                }
                """.formatted(jsonString(MODEL), jsonString(rendered));

        URI uri = URI.create("https://api.anthropic.com/v1/messages");
        long start = System.nanoTime();
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        if (response.statusCode() / 100 != 2) {
            return new TranslateOutcome(
                    SystemId.CLAUDE,
                    OutcomeKind.ERROR,
                    response.statusCode(),
                    "",
                    response.body(),
                    latencyMs,
                    OutcomeKind.ERROR.name());
        }
        String raw = extractClaudeText(response.body());
        String sql = LlmText.stripMarkdownFences(raw);
        store.write(
                SystemId.CLAUDE,
                caseKey,
                request.source(),
                request.target(),
                sql,
                MODEL,
                PromptTemplate.VERSION,
                Instant.now().toString(),
                latencyMs);
        return new TranslateOutcome(
                SystemId.CLAUDE, OutcomeKind.SUCCESS, 0, sql, "", latencyMs, OutcomeKind.SUCCESS.name());
    }

    private static TranslateOutcome noFixture() {
        return new TranslateOutcome(
                SystemId.CLAUDE, OutcomeKind.NO_FIXTURE, -1, "", "missing fixture", 0L,
                OutcomeKind.NO_FIXTURE.name());
    }

    static String extractClaudeText(String json) {
        int typeIdx = json.indexOf("\"type\"");
        while (typeIdx >= 0) {
            int textType = json.indexOf("\"text\"", typeIdx);
            if (textType < 0 || textType > typeIdx + 40) {
                typeIdx = json.indexOf("\"type\"", typeIdx + 1);
                continue;
            }
            // content item: "type":"text" then "text":"..."
            int field = json.indexOf("\"text\"", textType + 5);
            if (field < 0) {
                return "";
            }
            int colon = json.indexOf(':', field + 5);
            if (colon < 0) {
                return "";
            }
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = q1 + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char n = json.charAt(++i);
                    sb.append(switch (n) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        default -> n;
                    });
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static String jsonString(String value) {
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
