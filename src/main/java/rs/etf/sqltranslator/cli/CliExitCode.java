package rs.etf.sqltranslator.cli;

/** Process exit codes — Phase 6 / Phase 7 harness contract. */
public final class CliExitCode {
    public static final int SUCCESS = 0;
    public static final int PARSE_ERROR = 1;
    public static final int UNSUPPORTED = 2;
    public static final int USAGE = 3;
    public static final int STRICT = 4;
    public static final int INTERNAL = 5;

    private CliExitCode() {
    }
}
