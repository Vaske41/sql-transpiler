package rs.etf.sqltranslator.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Optional entry point for evaluation / fixture regen (test classpath).
 *
 * <p>Prefer {@code mvn -Dtest=BenchmarkDriverOfflineTest test} for CI-safe smoke.
 * Full corpus (no Docker / no live LLM):
 *
 * <pre>
 *   java -cp &lt;test+runtime classpath&gt; rs.etf.sqltranslator.evaluation.EvaluationMain
 * </pre>
 *
 * <p>Live Composer fixture regen (requires {@code EVAL_LIVE=1} or {@code -Deval.live=true}
 * and {@code CURSOR_API_KEY}; never CI). Offline {@link BenchmarkDriver} paths keep Composer
 * {@code forceOffline=true} via {@code fixtureLlmAdapters()}:
 *
 * <pre>
 *   java -cp &lt;test+runtime classpath&gt; rs.etf.sqltranslator.evaluation.EvaluationMain \
 *        --live-composer [--limit N]
 * </pre>
 */
public final class EvaluationMain {

    private EvaluationMain() {
    }

    public static void main(String[] args) throws Exception {
        if (contains(args, "--live-composer")) {
            runLiveComposer(args);
            return;
        }
        Path jar = BenchmarkDriver.DEFAULT_JAR;
        if (!Files.isRegularFile(jar)) {
            System.err.println("Missing " + jar.toAbsolutePath() + " — run mvn package first");
            System.exit(3);
        }
        Path csv = BenchmarkDriver.DEFAULT_CSV;
        boolean sqlglot = contains(args, "--sqlglot");
        BenchmarkDriver driver = BenchmarkDriver.fullOffline(jar, csv, sqlglot);
        List<ScoreRow> rows = driver.run();
        System.out.println("Wrote " + rows.size() + " rows to " + csv.toAbsolutePath());
    }

    private static void runLiveComposer(String[] args) throws Exception {
        if (!LlmText.liveEnabled()) {
            System.err.println(
                    "Usage: EvaluationMain --live-composer [--limit N]\n"
                            + "Requires EVAL_LIVE=1 or -Deval.live=true and CURSOR_API_KEY.");
            System.exit(2);
        }
        String key = System.getenv("CURSOR_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println(
                    "Usage: EvaluationMain --live-composer [--limit N]\n"
                            + "Requires EVAL_LIVE=1 or -Deval.live=true and CURSOR_API_KEY.");
            System.exit(2);
        }
        int limit = parseLimit(args);
        Path csv = BenchmarkDriver.DEFAULT_CSV;
        BenchmarkDriver driver = BenchmarkDriver.liveComposerRegen(csv, limit);
        List<ScoreRow> rows = driver.run();
        System.out.println("Wrote " + rows.size() + " rows to " + csv.toAbsolutePath());
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
