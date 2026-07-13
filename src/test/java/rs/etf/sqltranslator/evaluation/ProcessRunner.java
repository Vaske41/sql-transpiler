package rs.etf.sqltranslator.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Shared UTF-8-oriented {@link ProcessBuilder} helper for Failsafe jar / external-tool ITs.
 */
final class ProcessRunner {

    private ProcessRunner() {
    }

    record Result(int exitCode, byte[] stdout, byte[] stderr, long latencyMs) {
        String stdoutUtf8() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        String stderrUtf8() {
            return new String(stderr, StandardCharsets.UTF_8);
        }
    }

    static Result run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        long start = System.nanoTime();
        Process process = pb.start();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exit = process.waitFor();
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new Result(exit, stdout, stderr, latencyMs);
    }

    static String javaExecutable() {
        String home = System.getProperty("java.home");
        Path bin = Path.of(home, "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        return bin.toString();
    }
}
