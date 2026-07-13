package rs.etf.sqltranslator.evaluation;

/**
 * Shared LLM text helpers (fence stripping). Package-private.
 */
final class LlmText {

    private LlmText() {
    }

    /** Removes a leading {@code ```}/{@code ```sql} fence and trailing {@code ```} if present. */
    static String stripMarkdownFences(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        if (!t.startsWith("```")) {
            return text;
        }
        int firstNl = t.indexOf('\n');
        if (firstNl < 0) {
            return "";
        }
        t = t.substring(firstNl + 1);
        int fence = t.lastIndexOf("```");
        if (fence >= 0) {
            t = t.substring(0, fence);
        }
        return t.trim();
    }

    static boolean liveEnabled() {
        if (Boolean.getBoolean("eval.live")) {
            return true;
        }
        String env = System.getenv("EVAL_LIVE");
        return "1".equals(env) || "true".equalsIgnoreCase(env);
    }
}
