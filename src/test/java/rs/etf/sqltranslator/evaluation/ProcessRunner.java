package rs.etf.sqltranslator.evaluation;

import java.io.IOException;
import java.io.OutputStream;
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
        return run(command, null);
    }

    /**
     * @param stdinUtf8 optional stdin bytes (closed after write); {@code null} closes stdin empty
     */
    static Result run(List<String> command, byte[] stdinUtf8)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        long start = System.nanoTime();
        Process process = pb.start();
        try (OutputStream stdin = process.getOutputStream()) {
            if (stdinUtf8 != null && stdinUtf8.length > 0) {
                stdin.write(stdinUtf8);
            }
        }
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exit = process.waitFor();
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return new Result(exit, stdout, stderr, latencyMs);
    }

    /**
     * Timed process run. On expiry: {@code destroyForcibly}, exit {@code -1}, stderr notes
     * {@code timeout}.
     *
     * @param stdinUtf8 optional stdin bytes (closed after write); {@code null} closes stdin empty
     * @param timeoutSeconds maximum wall time before forced destroy ({@code > 0})
     */
    static Result run(List<String> command, byte[] stdinUtf8, long timeoutSeconds)
            throws IOException, InterruptedException {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        long start = System.nanoTime();
        Process process = pb.start();
        try (OutputStream stdin = process.getOutputStream()) {
            if (stdinUtf8 != null && stdinUtf8.length > 0) {
                stdin.write(stdinUtf8);
            }
        }
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
            byte[] stdout = process.getInputStream().readAllBytes();
            byte[] stderrRaw = process.getErrorStream().readAllBytes();
            String stderrNote = "timeout after " + timeoutSeconds + "s\n";
            byte[] noteBytes = stderrNote.getBytes(StandardCharsets.UTF_8);
            byte[] stderr = new byte[stderrRaw.length + noteBytes.length];
            System.arraycopy(stderrRaw, 0, stderr, 0, stderrRaw.length);
            System.arraycopy(noteBytes, 0, stderr, stderrRaw.length, noteBytes.length);
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            return new Result(-1, stdout, stderr, latencyMs);
        }
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        int exit = process.exitValue();
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
