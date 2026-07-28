package rs.etf.sqltranslator.ast;

/** Shape of a {@code GROUP BY} clause beyond a plain column list. */
public enum GroupByKind {
    PLAIN,
    ROLLUP,
    CUBE,
    GROUPING_SETS
}
