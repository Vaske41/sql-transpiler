package rs.etf.sqltranslator.cli;

/**
 * CLI-layer usage / argv / input-resolution failures. Distinct from pipeline
 * {@link IllegalArgumentException}s, which must surface as exit
 * {@link CliExitCode#INTERNAL}.
 */
final class CliUsageException extends RuntimeException {

    CliUsageException(String message) {
        super(message);
    }
}
