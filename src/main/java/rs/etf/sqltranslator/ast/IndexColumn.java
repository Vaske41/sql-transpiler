package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/**
 * One indexed column; {@code direction} is ASC when the source wrote none.
 * Deliberately an {@link Identifier}, not a {@code ColumnRef}: expression-level
 * rewrite rules must never touch index DDL.
 */
public record IndexColumn(Identifier column, SortDirection direction, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitIndexColumn(this);
    }
}
