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
 * Gemini baseline ({@code gemini-2.5-flash}, temperature 0). Fixture-first; live only with
 * {@code eval.live}/{@code EVAL_LIVE} and {@code GEMINI_API_KEY}.
 */
final class GeminiAdapter implements TranslatorAdapter {

    static final String MODEL = "gemini-2.5-flash";
    private static final String API_KEY_ENV = "GEMINI_API_KEY";

    private final FixtureStore store;
    private final PromptTemplate prompt;
    private final HttpClient http;
    private final boolean forceOffline;

    GeminiAdapter() throws Exception {
        this(new FixtureStore(), PromptTemplate.load(), HttpClient.newHttpClient(), false);
    }

    GeminiAdapter(FixtureStore store, PromptTemplate prompt, HttpClient http, boolean forceOffline) {
        this.store = Objects.requireNonNull(store, "store");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.http = Objects.requireNonNull(http, "http");
        this.forceOffline = forceOffline;
    }

    @Override
    public SystemId systemId() {
        return SystemId.GEMINI;
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

        Optional<String> fixture = store.readSql(SystemId.GEMINI, caseKey, source, target);
        if (fixture.isEmpty()) {
            return noFixture();
        }
        String sql = LlmText.stripMarkdownFences(fixture.get());
        long latency = parseLatencyMs(store.readMeta(SystemId.GEMINI, caseKey, source, target).orElse(""));
        return new TranslateOutcome(
                SystemId.GEMINI, OutcomeKind.SUCCESS, 0, sql, "", latency, OutcomeKind.SUCCESS.name());
    }

    private TranslateOutcome liveTranslate(TranslateRequest request, String caseKey, String apiKey)
            throws Exception {
        String inputSql = Files.readString(request.inputFile(), StandardCharsets.UTF_8);
        String rendered = prompt.render(request.source(), request.target(), inputSql);
        String body = """
                {
                  "contents": [{"parts":[{"text":%s}]}],
                  "generationConfig": {"temperature": 0}
                }
                """.formatted(jsonString(rendered));

        URI uri = URI.create(
                "https://generativelanguage.googleapis.com/v1beta/models/"
                        + MODEL
                        + ":generateContent?key="
                        + apiKey);
        long start = System.nanoTime();
        HttpRequest httpRequest = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        if (response.statusCode() / 100 != 2) {
            return new TranslateOutcome(
                    SystemId.GEMINI,
                    OutcomeKind.ERROR,
                    response.statusCode(),
                    "",
                    response.body(),
                    latencyMs,
                    OutcomeKind.ERROR.name());
        }
        String raw = extractGeminiText(response.body());
        String sql = LlmText.stripMarkdownFences(raw);
        store.write(
                SystemId.GEMINI,
                caseKey,
                request.source(),
                request.target(),
                sql,
                MODEL,
                PromptTemplate.VERSION,
                Instant.now().toString(),
                latencyMs);
        return new TranslateOutcome(
                SystemId.GEMINI, OutcomeKind.SUCCESS, 0, sql, "", latencyMs, OutcomeKind.SUCCESS.name());
    }

    private static TranslateOutcome noFixture() {
        return new TranslateOutcome(
                SystemId.GEMINI, OutcomeKind.NO_FIXTURE, -1, "", "missing fixture", 0L,
                OutcomeKind.NO_FIXTURE.name());
    }

    static String extractGeminiText(String json) {
        // Minimal parse: first "text": "..." string value under candidates.
        String marker = "\"text\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return "";
        }
        int colon = json.indexOf(':', idx + marker.length());
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

    static long parseLatencyMs(String metaJson) {
        String marker = "\"latencyMs\"";
        int idx = metaJson.indexOf(marker);
        if (idx < 0) {
            return 0L;
        }
        int colon = metaJson.indexOf(':', idx + marker.length());
        if (colon < 0) {
            return 0L;
        }
        int end = colon + 1;
        while (end < metaJson.length() && Character.isWhitespace(metaJson.charAt(end))) {
            end++;
        }
        int start = end;
        while (end < metaJson.length() && Character.isDigit(metaJson.charAt(end))) {
            end++;
        }
        if (start == end) {
            return 0L;
        }
        return Long.parseLong(metaJson.substring(start, end));
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
