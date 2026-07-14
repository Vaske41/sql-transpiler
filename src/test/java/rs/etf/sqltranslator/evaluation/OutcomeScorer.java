package rs.etf.sqltranslator.evaluation;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Applies Phase 7 scoring semantics on top of adapter raw outcomes.
 *
 * <p>sqltranslate / SQLGlot adapters already classify exit codes; LLM fixtures return
 * SUCCESS for any non-empty SQL — this scorer remaps unsupported invention to
 * {@link OutcomeKind#WRONG_INVENTION}. Empty LLM output on unsupported cases is
 * {@link OutcomeKind#REFUSED} (not {@link OutcomeKind#REFUSED_OK}); {@code REFUSED_OK}
 * is reserved for sqltranslate exit code 2 on unsupported inputs.
 */
final class OutcomeScorer {

    private OutcomeScorer() {
    }

    static boolean isUnsupportedCase(Path casePath) {
        Objects.requireNonNull(casePath, "casePath");
        return casePath.toString().replace('\\', '/').toLowerCase(Locale.ROOT).contains("/unsupported/");
    }

    /**
     * Single-statement heuristic for the LLM scoring subset: after stripping a trailing
     * semicolon, no additional {@code ;} remains.
     */
    static boolean isSingleStatement(String sql) {
        if (sql == null) {
            return true;
        }
        String t = sql.trim();
        if (t.isEmpty()) {
            return true;
        }
        if (t.endsWith(";")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return !t.contains(";");
    }

    static OutcomeKind score(SystemId system, Path casePath, TranslateOutcome raw) {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(casePath, "casePath");
        Objects.requireNonNull(raw, "raw");

        if (system == SystemId.GEMINI || system == SystemId.COMPOSER) {
            return scoreLlm(casePath, raw);
        }
        // Jar applies REFUSED_OK (exit 2) / WRONG_INVENTION; SQLGlot never emits REFUSED_OK
        return raw.outcome();
    }

    private static OutcomeKind scoreLlm(Path casePath, TranslateOutcome raw) {
        OutcomeKind base = raw.outcome();
        if (base == OutcomeKind.NO_FIXTURE
                || base == OutcomeKind.ERROR
                || base == OutcomeKind.PARSE
                || base == OutcomeKind.INTERNAL) {
            return base;
        }
        boolean unsupported = isUnsupportedCase(casePath);
        boolean nonEmpty = raw.sql() != null && !raw.sql().isBlank();
        if (unsupported) {
            return nonEmpty ? OutcomeKind.WRONG_INVENTION : OutcomeKind.REFUSED;
        }
        return nonEmpty ? OutcomeKind.SUCCESS : OutcomeKind.REFUSED;
    }
}
