package rs.etf.sqltranslator.codegen;

/**
 * Minimal token writer for SQL text: {@code token} inserts a single separating
 * space (suppressed at start and after an opening parenthesis), {@code raw}
 * appends verbatim. All formatting decisions live in the printers; this class
 * only guarantees the spacing discipline the canonical style depends on.
 */
public final class SqlWriter {

    private final StringBuilder sb = new StringBuilder();
    private boolean fuse;

    /** Suppress the separating space before the next token (unary sign fusion). */
    public SqlWriter fuse() {
        fuse = true;
        return this;
    }

    public SqlWriter token(String text) {
        if (!fuse && sb.length() > 0) {
            char last = sb.charAt(sb.length() - 1);
            if (last != '(' && last != ' ' && last != '\n') {
                sb.append(' ');
            }
        }
        fuse = false;
        sb.append(text);
        return this;
    }

    public SqlWriter raw(String text) {
        sb.append(text);
        return this;
    }

    public String result() {
        return sb.toString();
    }
}
