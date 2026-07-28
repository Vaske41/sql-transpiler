package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/**
 * One index key entry; {@code direction} is ASC when the source wrote none.
 * Keys may be plain columns or expressions (functional indexes).
 */
public record IndexColumn(Expression key, SortDirection direction, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitIndexColumn(this);
    }
}
