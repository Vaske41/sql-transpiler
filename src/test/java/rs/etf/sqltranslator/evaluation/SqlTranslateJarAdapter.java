package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.cli.CliExitCode;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * sqltranslate baseline via fat-jar ProcessBuilder — never calls
 * {@code Translator.translate}.
 */
final class SqlTranslateJarAdapter implements TranslatorAdapter {

    private final Path jar;

    SqlTranslateJarAdapter(Path jar) {
        this.jar = Objects.requireNonNull(jar, "jar");
    }

    @Override
    public SystemId systemId() {
        return SystemId.SQLTRANSLATE;
    }

    @Override
    public TranslateOutcome translate(TranslateRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        ProcessRunner.Result run = ProcessRunner.run(List.of(
                ProcessRunner.javaExecutable(),
                "-jar", jar.toAbsolutePath().toString(),
                "--from", cliName(request.source()),
                "--to", cliName(request.target()),
                "--in", request.inputFile().toAbsolutePath().toString()));
        OutcomeKind outcome = classifySqlTranslate(run.exitCode(), request.casePath());
        return new TranslateOutcome(
                SystemId.SQLTRANSLATE,
                outcome,
                run.exitCode(),
                run.stdoutUtf8(),
                run.stderrUtf8(),
                run.latencyMs(),
                outcome.name());
    }

    static OutcomeKind classifySqlTranslate(int exit, Path casePath) {
        boolean unsupportedCase = casePath.toString().replace('\\', '/').contains("/unsupported/");
        if (exit == CliExitCode.SUCCESS) {
            return unsupportedCase ? OutcomeKind.WRONG_INVENTION : OutcomeKind.SUCCESS;
        }
        if (exit == CliExitCode.UNSUPPORTED) {
            return unsupportedCase ? OutcomeKind.REFUSED_OK : OutcomeKind.REFUSED;
        }
        if (exit == CliExitCode.PARSE_ERROR) {
            return OutcomeKind.PARSE;
        }
        if (exit == CliExitCode.INTERNAL) {
            return OutcomeKind.INTERNAL;
        }
        return OutcomeKind.ERROR;
    }

    private static String cliName(Dialect dialect) {
        return dialect.name().toLowerCase(Locale.ROOT);
    }
}
