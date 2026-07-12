package rs.etf.sqltranslator.cli;

import picocli.CommandLine;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Fat-jar / {@code java -jar} entry point. */
public final class SqlTranslateMain {

    private SqlTranslateMain() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(System.err, true, StandardCharsets.UTF_8);
        System.exit(execute(args, System.in, out, err));
    }

    /**
     * Testable entry. Picocli invalid-input exits map to {@link CliExitCode#USAGE} (3),
     * distinct from {@link CliExitCode#UNSUPPORTED} (2) and {@link CliExitCode#STRICT} (4).
     */
    public static int execute(String[] args, InputStream in, PrintStream out, PrintStream err) {
        // Tests pass non-TTY (piped ByteArrayInputStream). Production main uses console().
        boolean tty = System.console() != null && in == System.in;
        SqlTranslateCommand command = new SqlTranslateCommand(
                new InputStreamReader(in, StandardCharsets.UTF_8), out, err, tty);
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        cmd.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
        cmd.getCommandSpec().exitCodeOnInvalidInput(CliExitCode.USAGE);
        cmd.getCommandSpec().exitCodeOnExecutionException(CliExitCode.INTERNAL);
        return cmd.execute(args);
    }
}
