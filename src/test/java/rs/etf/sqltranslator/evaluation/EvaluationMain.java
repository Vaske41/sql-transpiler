package rs.etf.sqltranslator.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Optional entry point for a full offline evaluation run (test classpath).
 *
 * <p>Prefer {@code mvn -Dtest=BenchmarkDriverOfflineTest test} for CI-safe smoke.
 * Full corpus (no Docker / no live LLM):
 *
 * <pre>
 *   java -cp &lt;test+runtime classpath&gt; rs.etf.sqltranslator.evaluation.EvaluationMain
 * </pre>
 */
public final class EvaluationMain {

    private EvaluationMain() {
    }

    public static void main(String[] args) throws Exception {
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
