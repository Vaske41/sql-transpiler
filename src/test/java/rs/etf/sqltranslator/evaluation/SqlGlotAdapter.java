package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLGlot baseline via {@code evaluation/bin/sqlglot_transpile.py} (schema-aware helper).
 */
final class SqlGlotAdapter implements TranslatorAdapter {

    static final Path HELPER = Path.of("evaluation", "bin", "sqlglot_transpile.py");

    private final Path helper;
    private final String python;

    SqlGlotAdapter() {
        this(HELPER, resolvePython().orElse("python"));
    }

    SqlGlotAdapter(Path helper, String python) {
        this.helper = Objects.requireNonNull(helper, "helper");
        this.python = Objects.requireNonNull(python, "python");
    }

    @Override
    public SystemId systemId() {
        return SystemId.SQLGLOT;
    }

    @Override
    public TranslateOutcome translate(TranslateRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        byte[] sql = Files.readAllBytes(request.inputFile());
        ProcessRunner.Result run = ProcessRunner.run(
                List.of(
                        python,
                        helper.toAbsolutePath().toString(),
                        "--read", cliName(request.source()),
                        "--write", cliName(request.target())),
                sql);
        OutcomeKind outcome = classify(run.exitCode(), request.casePath(), run.stdoutUtf8());
        return new TranslateOutcome(
                SystemId.SQLGLOT,
                outcome,
                run.exitCode(),
                run.stdoutUtf8(),
                run.stderrUtf8(),
                run.latencyMs(),
                outcome.name());
    }

    static OutcomeKind classify(int exit, Path casePath, String sql) {
        boolean unsupported = casePath.toString().replace('\\', '/').contains("/unsupported/");
        if (exit == 0) {
            if (unsupported && sql != null && !sql.isBlank()) {
                return OutcomeKind.WRONG_INVENTION;
            }
            return OutcomeKind.SUCCESS;
        }
        return unsupported ? OutcomeKind.REFUSED : OutcomeKind.ERROR;
    }

    static boolean available() {
        if (!Files.isRegularFile(HELPER)) {
            return false;
        }
        Optional<String> py = resolvePython();
        if (py.isEmpty()) {
            return false;
        }
        try {
            ProcessRunner.Result probe = ProcessRunner.run(List.of(
                    py.get(), "-c", "import sqlglot"));
            return probe.exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static Optional<String> resolvePython() {
        for (String candidate : List.of("python", "python3")) {
            try {
                ProcessRunner.Result ver = ProcessRunner.run(List.of(candidate, "--version"));
                if (ver.exitCode() == 0) {
                    return Optional.of(candidate);
                }
            } catch (Exception ignored) {
                // try next
            }
        }
        return Optional.empty();
    }

    private static String cliName(Dialect dialect) {
        return dialect.name().toLowerCase(Locale.ROOT);
    }
}
