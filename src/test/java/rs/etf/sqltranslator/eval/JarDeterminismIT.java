package rs.etf.sqltranslator.eval;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.CaseFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failsafe IT: measure fat-jar stdout determinism via {@code ProcessBuilder}
 * (not in-process {@code Translator}). Writes JSON only under
 * {@code target/evaluation/determinism/}.
 *
 * <p>Uses a stratified representative subset (≥20 directions) rather than the
 * full corpus — full walk × 3 JVM starts exceeds the &lt;2 min Failsafe budget
 * on Windows.
 */
class JarDeterminismIT {

    private static final int RUNS = 3;
    /** Cap per source→target pair so total stays ≥20 and finishes quickly. */
    private static final int PER_DIRECTION = 4;
    private static final Path JAR = Path.of("target", "sqltranslate.jar");
    private static final Path REPORT =
            Path.of("target", "evaluation", "determinism", "latest.json");

    @Test
    void fatJarStdoutIsByteIdenticalAcrossRuns() throws Exception {
        assertThat(JAR)
                .as("fat jar must exist after package (run verify / package first)")
                .exists();

        CaseFiles corpus = CaseFiles.under("/cases", CodegenTestSupport::isCorpusInput);
        List<Sample> samples = selectRepresentative(corpus);
        assertThat(samples.size())
                .as("representative subset must cover ≥20 translation directions")
                .isGreaterThanOrEqualTo(20);

        List<String> failures = new ArrayList<>();
        int comparisons = 0;
        for (Sample sample : samples) {
            comparisons++;
            try {
                assertDeterministic(sample);
            } catch (AssertionError | IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                failures.add(sample.display + " -> " + sample.target + ": " + e.getMessage());
            }
        }

        writeReport(comparisons, failures.isEmpty(), failures);
        assertThat(failures)
                .as("fat-jar stdout must be byte-identical across %d runs", RUNS)
                .isEmpty();
    }

    private static void assertDeterministic(Sample sample) throws Exception {
        byte[] first = null;
        for (int i = 0; i < RUNS; i++) {
            RunResult run = invokeJar(sample.source, sample.target, sample.input);
            assertThat(run.exitCode)
                    .as("%s -> %s run %d exit", sample.display, sample.target, i + 1)
                    .isEqualTo(0);
            if (first == null) {
                first = run.stdout;
            } else {
                assertThat(run.stdout)
                        .as("%s -> %s run %d stdout", sample.display, sample.target, i + 1)
                        .isEqualTo(first);
            }
        }
    }

    private static RunResult invokeJar(Dialect source, Dialect target, Path input)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                javaExecutable(),
                "-jar", JAR.toAbsolutePath().toString(),
                "--from", cliName(source),
                "--to", cliName(target),
                "--in", input.toAbsolutePath().toString());
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process process = pb.start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            String err = new String(stderr, StandardCharsets.UTF_8);
            throw new AssertionError("exit " + exit + " stderr=" + err);
        }
        return new RunResult(exit, stdout);
    }

    private static List<Sample> selectRepresentative(CaseFiles corpus) {
        Map<String, Integer> perPair = new HashMap<>();
        List<Sample> out = new ArrayList<>();
        for (Path file : corpus.files()) {
            String display = corpus.displayName(file);
            Dialect source = dialectOf(file.getFileName().toString());
            for (Dialect target : Dialect.values()) {
                if (target == source) {
                    continue;
                }
                if (CodegenTestSupport.EXPECTED_REFUSALS.contains(display + "|" + target)) {
                    continue;
                }
                String pair = source + "->" + target;
                int n = perPair.getOrDefault(pair, 0);
                if (n >= PER_DIRECTION) {
                    continue;
                }
                perPair.put(pair, n + 1);
                out.add(new Sample(display, file, source, target));
            }
        }
        return out;
    }

    private static void writeReport(int comparisons, boolean identical, List<String> failures)
            throws IOException {
        Files.createDirectories(REPORT.getParent());
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"runs\": ").append(RUNS).append(",\n");
        json.append("  \"comparisons\": ").append(comparisons).append(",\n");
        json.append("  \"identical\": ").append(identical).append(",\n");
        json.append("  \"failures\": [");
        for (int i = 0; i < failures.size(); i++) {
            if (i > 0) {
                json.append(", ");
            }
            json.append('"').append(escapeJson(failures.get(i))).append('"');
        }
        json.append("]\n");
        json.append("}\n");
        Files.writeString(REPORT, json.toString(), StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String javaExecutable() {
        String home = System.getProperty("java.home");
        Path bin = Path.of(home, "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        return bin.toString();
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

    private record Sample(String display, Path input, Dialect source, Dialect target) {
    }

    private record RunResult(int exitCode, byte[] stdout) {
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof RunResult other)) {
                return false;
            }
            return exitCode == other.exitCode && Arrays.equals(stdout, other.stdout);
        }

        @Override
        public int hashCode() {
            return 31 * exitCode + Arrays.hashCode(stdout);
        }
    }
}
