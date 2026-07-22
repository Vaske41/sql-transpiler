package rs.etf.sqltranslator.ast;

/** One bound of a window frame ({@code UNBOUNDED PRECEDING}, {@code CURRENT ROW}, …). */
public enum FrameBoundKind {
    UNBOUNDED_PRECEDING, UNBOUNDED_FOLLOWING, CURRENT_ROW, PRECEDING, FOLLOWING
}
