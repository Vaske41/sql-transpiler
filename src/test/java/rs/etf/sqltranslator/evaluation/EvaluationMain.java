package rs.etf.sqltranslator.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional entry point for evaluation / fixture regen (test classpath).
 *
 * <p>Prefer {@code mvn -Dtest=BenchmarkDriverOfflineTest test} for CI-safe smoke.
 * Offline corpora:
 *
 * <pre>
 *   java -cp &lt;test+runtime classpath&gt; rs.etf.sqltranslator.evaluation.EvaluationMain \
 *        [--corpus golden|parrot-diverse] [--sqlglot] [--limit N]
 * </pre>
 *
 * <p>Live fixture regen (requires {@code EVAL_LIVE=1} or {@code -Deval.live=true}).
 * API keys resolve from process env <em>or</em> {@code evaluation/.env.local} overlay
 * (no JVM {@code setenv}; Composer child receives {@code CURSOR_API_KEY} via ProcessBuilder):
 *
 * <pre>
 *   java -cp &lt;test+runtime classpath&gt; rs.etf.sqltranslator.evaluation.EvaluationMain \
 *        --live-gemini|--live-composer --corpus parrot-diverse [--limit N]
 * </pre>
 */
public final class EvaluationMain {

    static final Path DEFAULT_PARROT_CASES =
            Path.of("evaluation", "datasets", "parrot", "cases");

    private EvaluationMain() {
    }

    public static void main(String[] args) throws Exception {
        if (contains(args, "--live-gemini")) {
            runLiveGemini(args);
            return;
        }
        if (contains(args, "--live-composer")) {
            runLiveComposer(args);
            return;
        }
        runOffline(args);
    }

    private static void runOffline(String[] args) throws Exception {
        Path jar = BenchmarkDriver.DEFAULT_JAR;
        if (!Files.isRegularFile(jar)) {
            System.err.println("Missing " + jar.toAbsolutePath() + " — run mvn package first");
            System.exit(3);
        }
        String corpus = parseCorpus(args);
        boolean sqlglot = contains(args, "--sqlglot");
        int limit = parseLimit(args);
        Path csv;
        BenchmarkDriver driver;
        if ("parrot-diverse".equals(corpus)) {
            Path casesRoot = DEFAULT_PARROT_CASES;
            if (!Files.isDirectory(casesRoot)) {
                System.err.println(
                        "Missing " + casesRoot.toAbsolutePath()
                                + " — run evaluation/bin/materialize_parrot.py first");
                System.exit(3);
            }
            csv = BenchmarkDriver.DEFAULT_PARROT_CSV;
            driver = BenchmarkDriver.parrotDiverseOffline(jar, csv, casesRoot, sqlglot, limit);
        } else {
            csv = BenchmarkDriver.DEFAULT_CSV;
            driver = BenchmarkDriver.fullOffline(jar, csv, sqlglot, limit);
        }
        List<ScoreRow> rows = driver.run();
        System.out.println("Wrote " + rows.size() + " rows to " + csv.toAbsolutePath());
    }

    private static void runLiveGemini(String[] args) throws Exception {
        requireLive("gemini");
        Map<String, String> fileEnv = EnvFiles.readLocal();
        Optional<String> key = EnvFiles.resolveSecret("GEMINI_API_KEY", fileEnv);
        if (key.isEmpty()) {
            System.err.println(
                    "Usage: EvaluationMain --live-gemini --corpus parrot-diverse [--limit N]\n"
                            + "Requires EVAL_LIVE=1 (or -Deval.live=true) and GEMINI_API_KEY "
                            + "via env or evaluation/.env.local.");
            System.exit(2);
        }
        String corpus = parseCorpus(args);
        int limit = parseLimit(args);
        Path parrotRoot = parrotRootForLive(corpus);
        Path csv = parrotRoot != null
                ? BenchmarkDriver.DEFAULT_PARROT_CSV
                : BenchmarkDriver.DEFAULT_CSV;
        BenchmarkDriver driver =
                BenchmarkDriver.liveGeminiRegen(csv, parrotRoot, limit, key.get());
        List<ScoreRow> rows = driver.run();
        System.out.println("Wrote " + rows.size() + " rows to " + csv.toAbsolutePath());
    }

    private static void runLiveComposer(String[] args) throws Exception {
        requireLive("composer");
        Map<String, String> fileEnv = EnvFiles.readLocal();
        Optional<String> key = EnvFiles.resolveSecret("CURSOR_API_KEY", fileEnv);
        if (key.isEmpty()) {
            System.err.println(
                    "Usage: EvaluationMain --live-composer --corpus parrot-diverse [--limit N]\n"
                            + "Requires EVAL_LIVE=1 (or -Deval.live=true) and CURSOR_API_KEY "
                            + "via env or evaluation/.env.local.");
            System.exit(2);
        }
        String corpus = parseCorpus(args);
        int limit = parseLimit(args);
        Path parrotRoot = parrotRootForLive(corpus);
        Path csv = parrotRoot != null
                ? BenchmarkDriver.DEFAULT_PARROT_CSV
                : BenchmarkDriver.DEFAULT_CSV;
        BenchmarkDriver driver =
                BenchmarkDriver.liveComposerRegen(csv, parrotRoot, limit, key.get());
        List<ScoreRow> rows = driver.run();
        System.out.println("Wrote " + rows.size() + " rows to " + csv.toAbsolutePath());
    }

    private static void requireLive(String which) {
        if (!LlmText.liveEnabled()) {
            System.err.println(
                    "Usage: EvaluationMain --live-" + which
                            + " --corpus parrot-diverse [--limit N]\n"
                            + "Requires EVAL_LIVE=1 or -Deval.live=true and API key via env "
                            + "or evaluation/.env.local.");
            System.exit(2);
        }
    }

    /**
     * @return parrot cases root, or {@code null} for golden corpus
     */
    private static Path parrotRootForLive(String corpus) {
        if ("parrot-diverse".equals(corpus)) {
            Path casesRoot = DEFAULT_PARROT_CASES;
            if (!Files.isDirectory(casesRoot)) {
                System.err.println(
                        "Missing " + casesRoot.toAbsolutePath()
                                + " — run evaluation/bin/materialize_parrot.py first");
                System.exit(3);
            }
            return casesRoot;
        }
        return null;
    }

    /**
     * {@code golden} (default) or {@code parrot-diverse}. Rejects bare {@code parrot}.
     */
    static String parseCorpus(String[] args) {
        if (args == null) {
            return "golden";
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("--corpus".equals(args[i])) {
                String value = args[i + 1];
                if ("parrot".equals(value)) {
                    System.err.println(
                            "Ambiguous --corpus parrot; use --corpus parrot-diverse "
                                    + "(PARROT-Diverse offline stress corpus).");
                    System.exit(2);
                }
                if ("golden".equals(value) || "parrot-diverse".equals(value)) {
                    return value;
                }
                System.err.println(
                        "Unknown --corpus " + value + "; expected golden|parrot-diverse");
                System.exit(2);
            }
        }
        return "golden";
    }

    private static int parseLimit(String[] args) {
        if (args == null) {
            return 0;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if ("--limit".equals(args[i])) {
                try {
                    int n = Integer.parseInt(args[i + 1]);
                    return Math.max(0, n);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid --limit value: " + args[i + 1]);
                    System.exit(2);
                }
            }
        }
        return 0;
    }

    private static boolean contains(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (flag.equals(a)) {
                return true;
            }
        }
        return false;
    }
}
