package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.CaseFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Walks the corpus, invokes configured adapters, scores outcomes, and writes CSV under
 * {@code target/evaluation/summary/}.
 */
final class BenchmarkDriver {

    static final Path DEFAULT_CSV =
            Path.of("target", "evaluation", "summary", "latest.csv");
    static final Path DEFAULT_JAR = Path.of("target", "sqltranslate.jar");

    /** Local adapters: N≥3 runs, drop first (warmup), median of remainder. */
    static final int LOCAL_LATENCY_RUNS = 3;

    private static final List<String> OFFLINE_SMOKE_CASES = List.of(
            "select-basic/select-literal/input.mysql.sql",
            "unsupported/hex-literal/input.mysql.sql",
            "select-basic/select-arithmetic/input.mysql.sql");

    private final List<TranslatorAdapter> adapters;
    private final Path csvOut;
    private final List<String> caseAllowlist;
    private final boolean llmSingleStatementOnly;
    private final int localLatencyRuns;

    BenchmarkDriver(
            List<TranslatorAdapter> adapters,
            Path csvOut,
            List<String> caseAllowlist,
            boolean llmSingleStatementOnly,
            int localLatencyRuns) {
        this.adapters = List.copyOf(Objects.requireNonNull(adapters, "adapters"));
        this.csvOut = Objects.requireNonNull(csvOut, "csvOut");
        this.caseAllowlist = caseAllowlist == null ? List.of() : List.copyOf(caseAllowlist);
        this.llmSingleStatementOnly = llmSingleStatementOnly;
        this.localLatencyRuns = Math.max(1, localLatencyRuns);
    }

    static BenchmarkDriver offlineLimited(Path jar, Path csvOut) throws Exception {
        return new BenchmarkDriver(
                offlineAdapters(jar),
                csvOut,
                OFFLINE_SMOKE_CASES,
                true,
                LOCAL_LATENCY_RUNS);
    }

    static BenchmarkDriver fullOffline(Path jar, Path csvOut, boolean includeSqlGlot)
            throws Exception {
        List<TranslatorAdapter> adapters = new ArrayList<>();
        adapters.add(new SqlTranslateJarAdapter(jar));
        if (includeSqlGlot && SqlGlotAdapter.available()) {
            adapters.add(new SqlGlotAdapter());
        }
        adapters.addAll(fixtureLlmAdapters());
        return new BenchmarkDriver(adapters, csvOut, List.of(), true, LOCAL_LATENCY_RUNS);
    }

    private static List<TranslatorAdapter> offlineAdapters(Path jar) throws Exception {
        List<TranslatorAdapter> adapters = new ArrayList<>();
        adapters.add(new SqlTranslateJarAdapter(jar));
        adapters.addAll(fixtureLlmAdapters());
        return adapters;
    }

    private static List<TranslatorAdapter> fixtureLlmAdapters() throws Exception {
        return List.of(
                new GeminiAdapter(
                        new FixtureStore(),
                        PromptTemplate.load(),
                        java.net.http.HttpClient.newHttpClient(),
                        true),
                new ComposerAdapter(new FixtureStore(), true));
    }

    List<ScoreRow> run() throws Exception {
        CaseFiles corpus = CaseFiles.under("/cases", BenchmarkDriver::isEvalInput);
        List<Path> inputs = selectInputs(corpus);

        List<ScoreRow> rows = new ArrayList<>();
        for (Path inputFile : inputs) {
            String display = corpus.displayName(inputFile);
            Dialect source = dialectOf(inputFile.getFileName().toString());
            Path caseDir = inputFile.getParent();
            String sqlText = Files.readString(inputFile, StandardCharsets.UTF_8);
            boolean singleStmt = OutcomeScorer.isSingleStatement(sqlText);

            for (Dialect target : Dialect.values()) {
                if (target == source) {
                    continue;
                }
                for (TranslatorAdapter adapter : adapters) {
                    SystemId system = adapter.systemId();
                    if (isLlm(system) && llmSingleStatementOnly && !singleStmt) {
                        continue;
                    }
                    ScoreRow row = scoreOne(adapter, display, source, target, caseDir, inputFile);
                    rows.add(row);
                }
            }
        }

        rows.sort(Comparator
                .comparing(ScoreRow::system)
                .thenComparing(ScoreRow::caseId)
                .thenComparing(ScoreRow::source)
                .thenComparing(ScoreRow::target));
        CsvResultsWriter.write(csvOut, rows);
        return rows;
    }

    private List<Path> selectInputs(CaseFiles corpus) {
        if (caseAllowlist.isEmpty()) {
            return corpus.files();
        }
        List<Path> ordered = new ArrayList<>();
        for (String display : caseAllowlist) {
            for (Path file : corpus.files()) {
                if (display.equals(corpus.displayName(file))) {
                    ordered.add(file);
                    break;
                }
            }
        }
        if (ordered.size() != caseAllowlist.size()) {
            throw new IllegalStateException(
                    "Offline allowlist cases missing from corpus: wanted="
                            + caseAllowlist.size() + " found=" + ordered.size());
        }
        return ordered;
    }

    private ScoreRow scoreOne(
            TranslatorAdapter adapter,
            String display,
            Dialect source,
            Dialect target,
            Path caseDir,
            Path inputFile)
            throws Exception {
        TranslateRequest request = new TranslateRequest(source, target, caseDir, inputFile);
        SystemId system = adapter.systemId();
        boolean local = system == SystemId.SQLTRANSLATE || system == SystemId.SQLGLOT;

        TranslateOutcome last;
        long latencyMedian;
        String determinism;
        String notes = "";

        if (local && localLatencyRuns >= 3) {
            List<Long> latencies = new ArrayList<>();
            List<String> sqls = new ArrayList<>();
            last = null;
            for (int i = 0; i < localLatencyRuns; i++) {
                last = adapter.translate(request);
                latencies.add(last.latencyMs());
                sqls.add(last.sql() == null ? "" : last.sql());
            }
            List<Long> afterWarmup = latencies.subList(1, latencies.size());
            latencyMedian = median(afterWarmup);
            boolean identical = sqls.stream().distinct().count() <= 1;
            determinism = identical ? "true" : "false";
            notes = "local_latency_runs=" + localLatencyRuns + ";warmup_dropped";
        } else {
            last = adapter.translate(request);
            latencyMedian = last.latencyMs();
            determinism = "n/a";
            if (local) {
                notes = "single_run_latency";
            } else {
                notes = "llm_latency_from_fixture_or_live";
            }
        }

        OutcomeKind scored = OutcomeScorer.score(system, caseDir, last);
        return new ScoreRow(
                system.name().toLowerCase(Locale.ROOT),
                display,
                cliName(source),
                cliName(target),
                scored.name(),
                String.valueOf(last.exitCode()),
                "n/a",
                "n/a",
                determinism,
                String.valueOf(latencyMedian),
                notes);
    }

    static boolean isEvalInput(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return path.getFileName().toString().startsWith("input.")
                && !normalized.contains("/semantic/");
    }

    private static boolean isLlm(SystemId system) {
        return system == SystemId.GEMINI || system == SystemId.COMPOSER;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        if (n == 0) {
            return 0L;
        }
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2L;
    }

    private static String cliName(Dialect dialect) {
        return dialect.name().toLowerCase(Locale.ROOT);
    }

    private static Dialect dialectOf(String fileName) {
        String tag = fileName.substring("input.".length(), fileName.lastIndexOf('.'));
        return switch (tag) {
            case "tsql" -> Dialect.TSQL;
            case "mysql" -> Dialect.MYSQL;
            case "postgresql" -> Dialect.POSTGRESQL;
            default -> throw new IllegalArgumentException(fileName);
        };
    }
}
