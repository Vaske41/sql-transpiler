package rs.etf.sqltranslator.cli;

import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.ParseException;
import rs.etf.sqltranslator.core.TranslationOutput;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;
import rs.etf.sqltranslator.transform.Warning;

import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Pure CLI policy: resolve input, translate, write sinks, map failures to exit codes. */
public final class CliRunner {

    @FunctionalInterface
    interface TranslateFunction {
        TranslationOutput translate(String sql, Dialect source, Dialect target);
    }

    private CliRunner() {
    }

    public static int run(CliRequest request, Readable stdin, Appendable stdout, Appendable stderr) {
        return run(request, stdin, stdout, stderr, Translator::translate);
    }

    /**
     * Package-visible seam so tests can inject a pipeline failure without relying on
     * a golden SQL shape that still trips a printer after the rule engine rewrites it.
     */
    static int run(CliRequest request, Readable stdin, Appendable stdout, Appendable stderr,
                   TranslateFunction translate) {
        try {
            String sql = readSql(request, stdin);
            if (sql.isBlank()) {
                return fail(stderr, CliExitCode.USAGE, "usage", "empty SQL input");
            }
            TranslationOutput result = translate.translate(sql, request.source(), request.target());
            List<Warning> warnings = result.report().warnings();
            if (request.strict() && !warnings.isEmpty()) {
                writeReport(warnings, stderr);
                return fail(stderr, CliExitCode.STRICT, "strict",
                        warnings.size() + " warning(s)");
            }
            if (request.report()) {
                writeReport(warnings, stderr);
            }
            writeSql(result.sql(), request, stdout);
            return CliExitCode.SUCCESS;
        } catch (CliUsageException e) {
            return fail(stderr, CliExitCode.USAGE, "usage", e.getMessage());
        } catch (ParseException e) {
            return fail(stderr, CliExitCode.PARSE_ERROR, "parse", e.getMessage());
        } catch (UnsupportedFeatureException e) {
            return fail(stderr, CliExitCode.UNSUPPORTED, "unsupported", e.getMessage());
        } catch (IOException e) {
            return fail(stderr, CliExitCode.USAGE, "io", e.getMessage());
        } catch (RuntimeException e) {
            // Pipeline IllegalArgumentException / IllegalStateException → internal (not usage).
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return fail(stderr, CliExitCode.INTERNAL, "internal", msg);
        }
    }

    private static String readSql(CliRequest request, Readable stdin) throws IOException {
        boolean hasFile = request.inputFile() != null;
        boolean hasInline = request.inlineSql() != null;
        if (hasFile && hasInline) {
            throw new CliUsageException(
                    "Provide either --in FILE or inline SQL, not both");
        }
        if (!hasFile && !hasInline && request.stdinIsTty()) {
            throw new CliUsageException(
                    "missing SQL input (provide --in, positional SQL, or pipe stdin)");
        }
        if (hasFile) {
            return Files.readString(request.inputFile(), StandardCharsets.UTF_8);
        }
        if (hasInline) {
            return request.inlineSql();
        }
        return readFully(stdin);
    }

    private static String readFully(Readable readable) throws IOException {
        StringBuilder sb = new StringBuilder();
        CharBuffer buf = CharBuffer.allocate(4096);
        while (readable.read(buf) >= 0) {
            buf.flip();
            sb.append(buf);
            buf.clear();
        }
        return sb.toString();
    }

    private static void writeSql(String sql, CliRequest request, Appendable stdout)
            throws IOException {
        // Called only after successful translate — never open/truncate --out earlier.
        if (request.outFile() != null) {
            Files.writeString(request.outFile(), sql, StandardCharsets.UTF_8);
        } else {
            stdout.append(sql);
        }
    }

    private static void writeReport(List<Warning> warnings, Appendable stderr) throws IOException {
        for (Warning w : warnings) {
            stderr.append("warning ")
                    .append(w.code())
                    .append(" at ")
                    .append(w.position().toString())
                    .append(": ")
                    .append(w.message())
                    .append('\n');
        }
    }

    private static int fail(Appendable stderr, int code, String kind, String message) {
        try {
            stderr.append("error: ").append(kind).append(": ")
                    .append(message).append('\n');
        } catch (IOException ignored) {
            // still return the exit code
        }
        return code;
    }
}
