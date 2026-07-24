package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** Unifies TOP n / LIMIT n OFFSET m / OFFSET m ROWS FETCH NEXT n ROWS ONLY [WITH TIES]. */
public record RowLimit(Optional<Expression> count, Optional<Expression> offset,
                       boolean withTies, SourcePosition pos) implements AstNode {

    public RowLimit(Optional<Expression> count, Optional<Expression> offset, SourcePosition pos) {
        this(count, offset, false, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitRowLimit(this);
    }
}
