package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Path;

/**
 * One translation attempt. {@code casePath} is used for scoring classification
 * (e.g. whether the case lives under {@code /unsupported/}); {@code inputFile}
 * is passed to the tool as {@code --in}.
 */
record TranslateRequest(Dialect source, Dialect target, Path casePath, Path inputFile) {
}
