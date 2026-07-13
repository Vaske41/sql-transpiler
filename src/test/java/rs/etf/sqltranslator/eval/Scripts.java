package rs.etf.sqltranslator.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * Semicolon-based script helpers. Authoring rule: no {@code ;} inside literals/comments.
 */
final class Scripts {

    private Scripts() {
    }

    /**
     * Split a self-contained semantic script into setup statements and the final SELECT.
     *
     * @throws IllegalArgumentException if the script is empty or the last statement is not a SELECT
     */
    static SplitScript splitSetupAndFinalSelect(String script) {
        List<String> statements = splitStatements(script);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("script has no statements");
        }
        String last = statements.get(statements.size() - 1);
        if (!startsWithSelect(last)) {
            throw new IllegalArgumentException("last statement must be a SELECT, got: " + preview(last));
        }
        List<String> setup = List.copyOf(statements.subList(0, statements.size() - 1));
        return new SplitScript(setup, last);
    }

    static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        if (script == null || script.isBlank()) {
            return out;
        }
        for (String part : script.split(";")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static boolean startsWithSelect(String statement) {
        String s = statement.stripLeading();
        int len = s.length();
        if (len < 6) {
            return false;
        }
        return s.regionMatches(true, 0, "SELECT", 0, 6)
                && (len == 6 || Character.isWhitespace(s.charAt(6)) || s.charAt(6) == '(');
    }

    private static String preview(String s) {
        String oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }

    record SplitScript(List<String> setupStatements, String finalSelect) {
    }
}
