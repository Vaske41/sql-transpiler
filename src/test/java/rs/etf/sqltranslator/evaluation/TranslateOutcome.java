package rs.etf.sqltranslator.evaluation;

/**
 * Result of one adapter translation. {@code sql} is stdout (may be empty on refusal);
 * {@code status} is a short machine-readable tag (usually {@link OutcomeKind#name()}).
 */
record TranslateOutcome(
        SystemId system,
        OutcomeKind outcome,
        int exitCode,
        String sql,
        String stderr,
        long latencyMs,
        String status) {
}
