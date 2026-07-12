package rs.etf.sqltranslator.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import rs.etf.sqltranslator.core.Dialect;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "sqltranslate",
        mixinStandardHelpOptions = true,
        version = "sqltranslate 0.1.0-SNAPSHOT",
        description = "Translate SQL between T-SQL, MySQL, and PostgreSQL."
)
public final class SqlTranslateCommand implements Callable<Integer> {

    @Option(names = {"--from", "-f"}, required = true,
            description = "Source dialect: tsql, mysql, postgresql")
    String from;

    @Option(names = {"--to", "-t"}, required = true,
            description = "Target dialect: tsql, mysql, postgresql")
    String to;

    @Option(names = {"--in"}, description = "Input SQL file (UTF-8)")
    Path inFile;

    @Option(names = {"--out"}, description = "Write SQL to file instead of stdout")
    Path outFile;

    @Option(names = {"--strict"},
            description = "Treat translation warnings as errors (exit 4, no SQL written)")
    boolean strict;

    @Option(names = {"--report"},
            description = "Print translation warnings to stderr")
    boolean report;

    @Parameters(arity = "0..1", description = "Inline SQL (omit to read stdin or --in)")
    String sql;

    private final Reader stdin;
    private final Appendable stdout;
    private final Appendable stderr;
    private final boolean stdinIsTty;

    public SqlTranslateCommand() {
        this(new InputStreamReader(System.in, StandardCharsets.UTF_8),
                System.out, System.err, System.console() != null);
    }

    /** Test / main injection. */
    SqlTranslateCommand(Reader stdin, Appendable stdout, Appendable stderr, boolean stdinIsTty) {
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
        this.stdinIsTty = stdinIsTty;
    }

    @Override
    public Integer call() {
        try {
            Dialect source = Dialect.fromCliName(from);
            Dialect target = Dialect.fromCliName(to);
            CliRequest request = new CliRequest(
                    source, target, inFile, sql, outFile, strict, report, stdinIsTty);
            return CliRunner.run(request, stdin, stdout, stderr);
        } catch (IllegalArgumentException e) {
            try {
                stderr.append("error: usage: ").append(e.getMessage())
                        .append(System.lineSeparator());
            } catch (Exception ignored) {
                // ignore secondary failure
            }
            return CliExitCode.USAGE;
        }
    }
}
