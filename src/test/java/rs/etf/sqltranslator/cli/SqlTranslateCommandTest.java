package rs.etf.sqltranslator.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTranslateCommandTest {

    @TempDir
    Path tmp;

    @Test
    void helpExitsZero() {
        Capture cap = run("--help");
        assertThat(cap.code).isEqualTo(0);
        assertThat(cap.out).contains("Usage:");
    }

    @Test
    void versionPinsPomSnapshot() {
        Capture cap = run("--version");
        assertThat(cap.code).isEqualTo(0);
        assertThat(cap.out).contains("0.1.0-SNAPSHOT");
    }

    @Test
    void missingRequiredOptionsExitsThree() {
        Capture cap = run("SELECT 1");
        assertThat(cap.code).isEqualTo(CliExitCode.USAGE);
    }

    @Test
    void inlineTranslateSample() {
        Capture cap = run(
                "--from", "tsql",
                "--to", "postgresql",
                "SELECT TOP 3 name FROM users ORDER BY id;");
        assertThat(cap.code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(cap.out).isEqualTo(
                "SELECT name FROM users ORDER BY id NULLS FIRST LIMIT 3;\n");
        assertThat(cap.err).isEmpty();
    }

    @Test
    void fileInAndOutAndReport() throws Exception {
        Path in = tmp.resolve("in.sql");
        Path out = tmp.resolve("out.sql");
        Files.writeString(in,
                "SELECT a FROM t ORDER BY a DESC NULLS LAST;",
                StandardCharsets.UTF_8);
        Capture cap = run(
                "--from", "postgresql",
                "--to", "mysql",
                "--in", in.toString(),
                "--out", out.toString(),
                "--report");
        assertThat(cap.code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(cap.out).isEmpty();
        assertThat(Files.readString(out, StandardCharsets.UTF_8)).isNotBlank();
        assertThat(cap.err).contains("warning NULLS_ORDERING_DROPPED");
    }

    @Test
    void stdinPipeline() {
        Capture cap = runWithStdin(
                "SELECT 1;",
                "--from", "mysql",
                "--to", "tsql");
        assertThat(cap.code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(cap.out).isEqualTo("SELECT 1;\n");
    }

    @Test
    void unknownDialectExitsThree() {
        Capture cap = run("--from", "sqlite", "--to", "mysql", "SELECT 1;");
        assertThat(cap.code).isEqualTo(CliExitCode.USAGE);
        assertThat(cap.err).contains("error: usage: ");
        assertThat(cap.err).contains("Unknown dialect");
    }

    @Test
    void strictViaCliExitsFour() {
        Capture cap = run(
                "--from", "postgresql",
                "--to", "mysql",
                "--strict",
                "SELECT a FROM t ORDER BY a DESC NULLS LAST;");
        assertThat(cap.code).isEqualTo(CliExitCode.STRICT);
        assertThat(cap.out).isEmpty();
        assertThat(cap.err).contains("error: strict: ");
    }

    private static Capture run(String... args) {
        return runWithStdin("", args);
    }

    private static Capture runWithStdin(String stdin, String... args) {
        ByteArrayInputStream in =
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code = SqlTranslateMain.execute(
                args, in, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        return new Capture(code,
                outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
