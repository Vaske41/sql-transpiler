package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** One {@code UNION|EXCEPT|INTERSECT [ALL] querySpecification} arm of a {@link Query}. */
public record UnionArm(SetOperator operator, boolean all, QuerySpecification spec, SourcePosition pos)
        implements AstNode {

    /** Backward-compatible UNION constructor. */
    public UnionArm(boolean all, QuerySpecification spec, SourcePosition pos) {
        this(SetOperator.UNION, all, spec, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUnionArm(this);
    }
}
