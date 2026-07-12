package rs.etf.sqltranslator.core;

import rs.etf.sqltranslator.transform.TranslationReport;

/** Final product of a translation: target SQL text plus the warning report. */
public record TranslationOutput(String sql, TranslationReport report) {
}
