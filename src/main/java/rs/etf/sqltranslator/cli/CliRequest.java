package rs.etf.sqltranslator.cli;

import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Path;

/**
 * Bound CLI options. {@code stdinIsTty} is set by the command layer
 * ({@code System.console() != null}) so the runner can fail fast without hanging.
 */
public record CliRequest(
        Dialect source,
        Dialect target,
        Path inputFile,
        String inlineSql,
        Path outFile,
        boolean strict,
        boolean report,
        boolean stdinIsTty
) {
}
