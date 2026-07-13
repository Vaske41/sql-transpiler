package rs.etf.sqltranslator.eval;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical cell/row formatting for ordered semantic result comparison.
 * Strings are never trimmed — padding bugs must stay visible.
 */
final class ResultSetNormalizer {

    private ResultSetNormalizer() {
    }

    static String normalizeValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (value instanceof Number n) {
            if (value instanceof Float || value instanceof Double) {
                return BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
            }
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof LocalDate d) {
            return d.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (value instanceof LocalTime t) {
            return t.format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        if (value instanceof LocalDateTime dt) {
            return dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof Date d) {
            return d.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        if (value instanceof Time t) {
            return t.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        if (value instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (value instanceof byte[] bytes && bytes.length == 1) {
            // MySQL BIT(1) often arrives as a single byte
            return (bytes[0] != 0) ? "1" : "0";
        }
        return value.toString();
    }

    static List<List<String>> normalize(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        List<List<String>> rows = new ArrayList<>();
        while (rs.next()) {
            List<String> row = new ArrayList<>(cols);
            for (int i = 1; i <= cols; i++) {
                row.add(normalizeValue(rs.getObject(i)));
            }
            rows.add(row);
        }
        return rows;
    }
}
