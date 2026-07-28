package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * One {@code UNION|EXCEPT|INTERSECT [ALL] operand} arm of a {@link Query}.
 * {@code operand} holds the full set-op subtree for the arm; {@code parenthesized}
 * preserves explicit grouping from {@code ( … )} in the source.
 */
public record UnionArm(SetOperator operator, boolean all, Query operand, boolean parenthesized,
                       SourcePosition pos) implements AstNode {

    /** Backward-compatible UNION constructor. */
    public UnionArm(boolean all, QuerySpecification spec, SourcePosition pos) {
        this(SetOperator.UNION, all, bareOperand(spec, pos), false, pos);
    }

    public UnionArm(SetOperator operator, boolean all, QuerySpecification spec, SourcePosition pos) {
        this(operator, all, bareOperand(spec, pos), false, pos);
    }

    /** First SELECT of this arm — convenience for transforms that only touch select lists. */
    public QuerySpecification spec() {
        return operand().first();
    }

    private static Query bareOperand(QuerySpecification spec, SourcePosition pos) {
        return new Query(List.of(), false, spec, List.of(), List.of(), Optional.empty(), pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUnionArm(this);
    }
}
