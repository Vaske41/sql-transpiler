package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/**
 * One endpoint of a window frame. Offset expressions are present only for
 * {@link FrameBoundKind#PRECEDING} / {@link FrameBoundKind#FOLLOWING}.
 */
public record FrameBound(FrameBoundKind kind, Optional<Expression> offset, SourcePosition pos)
        implements AstNode {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitFrameBound(this);
    }
}
