package rs.etf.sqltranslator.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.etf.sqltranslator.core.Dialect;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CliRunnerTest {

    @TempDir
    Path tmp;

    private static CliRequest inline(Dialect from, Dialect to, String sql) {
        return new CliRequest(from, to, null, sql, null, false, false, false);
    }

    @Test
    void successWritesSqlToStdout() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliRunner.run(
                inline(Dialect.TSQL, Dialect.POSTGRESQL,
                        "SELECT TOP 3 name FROM users ORDER BY id;"),
                new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(out.toString()).isEqualTo(
                "SELECT name FROM users ORDER BY id NULLS FIRST LIMIT 3;\n");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void successWritesUtf8NonAsciiThroughStdout() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliRunner.run(
                inline(Dialect.TSQL, Dialect.MYSQL,
                        "SELECT N'café' + name FROM users WHERE active = 1;"),
                new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(out.toString()).contains("café");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void successWritesSqlToOutFileNotStdout() throws Exception {
        Path dest = tmp.resolve("out.sql");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.MYSQL, Dialect.TSQL, null,
                "SELECT NOW() FROM t LIMIT 7;", dest, false, false, false);
        int code = CliRunner.run(req, new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(out.toString()).isEmpty();
        assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEqualTo(
                "SELECT TOP (7) GETDATE() FROM t;\n");
    }

    @Test
    void outFileNotTruncatedWhenTranslationFails() throws Exception {
        Path dest = tmp.resolve("keep.sql");
        Files.writeString(dest, "PRESERVE\n", StandardCharsets.UTF_8);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, null, "SELECTttt",
                dest, false, false, false);
        int code = CliRunner.run(req, new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.PARSE_ERROR);
        assertThat(Files.readString(dest, StandardCharsets.UTF_8)).isEqualTo("PRESERVE\n");
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).startsWith("error: parse: ");
    }

    @Test
    void parseErrorReturnsCode1WithPrefix() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliRunner.run(
                inline(Dialect.MYSQL, Dialect.POSTGRESQL, "SELECTttt"),
                new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.PARSE_ERROR);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).startsWith("error: parse: ");
        assertThat(err.toString()).contains("syntax error");
    }

    @Test
    void unsupportedFeatureReturnsCode2WithPrefix() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliRunner.run(
                inline(Dialect.POSTGRESQL, Dialect.MYSQL,
                        "SELECT u.id FROM users u FULL OUTER JOIN archived a ON a.id = u.id;"),
                new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.UNSUPPORTED);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).startsWith("error: unsupported: ");
    }

    @Test
    void reportPrintsWarningsToStderrAndStillSucceeds() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.POSTGRESQL, Dialect.MYSQL, null,
                "SELECT a FROM t ORDER BY a DESC NULLS LAST;",
                null, false, true, false);
        int code = CliRunner.run(req, new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(out.toString()).isNotBlank();
        assertThat(err.toString()).contains("warning NULLS_ORDERING_DROPPED");
    }

    @Test
    void withoutReportWarningsStaySilent() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.POSTGRESQL, Dialect.MYSQL, null,
                "SELECT a FROM t ORDER BY a DESC NULLS LAST;",
                null, false, false, false);
        int code = CliRunner.run(req, new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void strictWithWarningsExitsFourAndWritesNoSql() throws Exception {
        Path dest = tmp.resolve("should-not-exist.sql");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.POSTGRESQL, Dialect.MYSQL, null,
                "SELECT a FROM t ORDER BY a DESC NULLS LAST;",
                dest, true, false, false);
        int code = CliRunner.run(req, new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.STRICT);
        assertThat(out.toString()).isEmpty();
        assertThat(Files.exists(dest)).isFalse();
        assertThat(err.toString()).contains("warning NULLS_ORDERING_DROPPED");
        assertThat(err.toString()).contains("error: strict: ");
        assertThat(err.toString()).contains("warning(s)");
    }

    @Test
    void readsFromInputFile() throws Exception {
        Path in = tmp.resolve("in.sql");
        Files.writeString(in, "SELECT 1;", StandardCharsets.UTF_8);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, in, null, null, false, false, false);
        int code = CliRunner.run(req, new StringReader("SHOULD_NOT_BE_READ"), out, err);
        assertThat(code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(out.toString()).isEqualTo("SELECT 1;\n");
    }

    @Test
    void readsFromStdinWhenNoFileOrInline() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, null, null, null, false, false, false);
        int code = CliRunner.run(req, new StringReader("SELECT 2;"), out, err);
        assertThat(code).isEqualTo(CliExitCode.SUCCESS);
        assertThat(out.toString()).isEqualTo("SELECT 2;\n");
    }

    @Test
    void ttyWithoutInputIsUsageError() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL, null, null, null, false, false, true);
        int code = CliRunner.run(req, new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.USAGE);
        assertThat(err.toString()).startsWith("error: usage: ");
        assertThat(err.toString()).contains("missing SQL input");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void rejectsFileAndInlineTogether() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL,
                Path.of("x.sql"), "SELECT 1;", null, false, false, false);
        int code = CliRunner.run(req, new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.USAGE);
        assertThat(err.toString()).startsWith("error: usage: ");
        assertThat(err.toString()).contains("not both");
    }

    @Test
    void emptyInlineIsUsageError() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = CliRunner.run(
                inline(Dialect.MYSQL, Dialect.POSTGRESQL, "   "),
                new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.USAGE);
        assertThat(err.toString()).contains("empty SQL");
    }

    @Test
    void missingInputFileIsIoError() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CliRequest req = new CliRequest(
                Dialect.MYSQL, Dialect.POSTGRESQL,
                Path.of("no-such-file-sqltranslate-cli.sql"), null,
                null, false, false, false);
        int code = CliRunner.run(req, new StringReader(""), out, err);
        assertThat(code).isEqualTo(CliExitCode.USAGE);
        assertThat(err.toString()).startsWith("error: io: ");
        assertThat(out.toString()).isEmpty();
    }
}
