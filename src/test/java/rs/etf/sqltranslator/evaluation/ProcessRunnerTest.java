package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timed {@link ProcessRunner} must drain pipes concurrently (ProcessBuilder deadlock regression).
 */
class ProcessRunnerTest {

    @Test
    @EnabledIf("pythonAvailable")
    void timedRunDrainsLargeStdoutBeforeExit() throws Exception {
        // > typical OS pipe capacity (~64KiB). Wait-before-drain deadlocks until timeout.
        String script = "import sys; sys.stdout.buffer.write(b'x' * 200000); sys.stdout.buffer.flush()";
        long start = System.nanoTime();
        ProcessRunner.Result result = ProcessRunner.run(
                List.of(python(), "-c", script),
                null,
                15);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).hasSize(200_000);
        assertThat(elapsedMs)
                .as("must finish well under timeout if pipes are drained concurrently")
                .isLessThan(10_000);
    }

    @Test
    @EnabledIf("pythonAvailable")
    void timedRunReportsTimeout() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(
                List.of(python(), "-c", "import time; time.sleep(5)"),
                null,
                1);
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.stderrUtf8()).contains("timeout");
    }

    static boolean pythonAvailable() {
        return SqlGlotAdapter.resolvePython().isPresent();
    }

    private static String python() {
        Optional<String> py = SqlGlotAdapter.resolvePython();
        assertThat(py).isPresent();
        return py.get();
    }
}
