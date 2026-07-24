package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/**
 * One join step; {@code on} is empty exactly for {@link JoinKind#CROSS}
 * (and for T-SQL {@code OUTER APPLY}, modeled as LEFT + lateral with empty ON).
 */
public record Join(JoinKind kind, Relation table, Optional<Expression> on,
                   boolean lateral, SourcePosition pos)
        implements AstNode {

    /** Non-lateral join (existing call sites). */
    public Join(JoinKind kind, Relation table, Optional<Expression> on, SourcePosition pos) {
        this(kind, table, on, false, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitJoin(this);
    }
}
