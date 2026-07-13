package rs.etf.sqltranslator.evaluation;

/**
 * One CSV summary row. Columns match the Phase 7 design spec §7d.
 */
record ScoreRow(
        String system,
        String caseId,
        String source,
        String target,
        String outcome,
        String exitOrStatus,
        String syntacticValid,
        String semanticEquiv,
        String determinismOk,
        String latencyMsMedian,
        String notes) {
}
