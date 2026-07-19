package rs.etf.sqltranslator.evaluation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
     * Timed process run. Drains stdout/stderr concurrently so a chatty child cannot fill OS pipes
     * and deadlock before {@code waitFor}. On expiry: {@code destroyForcibly}, exit {@code -1},
     * stderr notes {@code timeout}.
     *
     * @param stdinUtf8 optional stdin bytes (closed after write); {@code null} closes stdin empty
     * @param timeoutSeconds maximum wall time before forced destroy ({@code > 0})
     */
    static Result run(List<String> command, byte[] stdinUtf8, long timeoutSeconds)
            throws IOException, InterruptedException {
        return run(command, stdinUtf8, timeoutSeconds, Map.of());
    }

    /**
     * Like {@link #run(List, byte[], long)} but merges {@code extraEnv} into the child environment
     * (e.g. {@code CURSOR_API_KEY} from {@code evaluation/.env.local} overlay).
     */
    static Result run(
            List<String> command, byte[] stdinUtf8, long timeoutSeconds, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be > 0");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        long start = System.nanoTime();
        Process process = pb.start();
        try (OutputStream stdin = process.getOutputStream()) {
            if (stdinUtf8 != null && stdinUtf8.length > 0) {
                stdin.write(stdinUtf8);
            }
        }
        CompletableFuture<byte[]> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("stdout drain failed", e);
            }
        });
        CompletableFuture<byte[]> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getErrorStream().readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException("stderr drain failed", e);
            }
        });
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        byte[] stdout = joinBytes(stdoutFuture);
        byte[] stderrRaw = joinBytes(stderrFuture);
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (!finished) {
            String stderrNote = "timeout after " + timeoutSeconds + "s\n";
            byte[] noteBytes = stderrNote.getBytes(StandardCharsets.UTF_8);
            byte[] stderr = new byte[stderrRaw.length + noteBytes.length];
            System.arraycopy(stderrRaw, 0, stderr, 0, stderrRaw.length);
            System.arraycopy(noteBytes, 0, stderr, stderrRaw.length, noteBytes.length);
            return new Result(-1, stdout, stderr, latencyMs);
        }
        return new Result(process.exitValue(), stdout, stderrRaw, latencyMs);
    }

    private static byte[] joinBytes(CompletableFuture<byte[]> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("stream drain failed", cause);
        }
    }

    static String javaExecutable() {
        String home = System.getProperty("java.home");
        Path bin = Path.of(home, "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        return bin.toString();
    }
}
