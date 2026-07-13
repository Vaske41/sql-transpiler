package rs.etf.sqltranslator.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Writes evaluation summary CSV under {@code target/evaluation/summary/}. */
final class CsvResultsWriter {

    static final String HEADER =
            "system,case_id,source,target,outcome,exit_or_status,syntactic_valid,semantic_equiv,"
                    + "determinism_ok,latency_ms_median,notes";

    private CsvResultsWriter() {
    }

    static void write(Path csvFile, List<ScoreRow> rows) throws IOException {
        Objects.requireNonNull(csvFile, "csvFile");
        Objects.requireNonNull(rows, "rows");
        Files.createDirectories(csvFile.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER).append('\n');
        for (ScoreRow row : rows) {
            sb.append(escape(row.system())).append(',')
                    .append(escape(row.caseId())).append(',')
                    .append(escape(row.source())).append(',')
                    .append(escape(row.target())).append(',')
                    .append(escape(row.outcome())).append(',')
                    .append(escape(row.exitOrStatus())).append(',')
                    .append(escape(row.syntacticValid())).append(',')
                    .append(escape(row.semanticEquiv())).append(',')
                    .append(escape(row.determinismOk())).append(',')
                    .append(escape(row.latencyMsMedian())).append(',')
                    .append(escape(row.notes()))
                    .append('\n');
        }
        Files.writeString(csvFile, sb.toString(), StandardCharsets.UTF_8);
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
