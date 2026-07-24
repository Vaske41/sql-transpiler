package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** {@code value IS [NOT] TRUE|FALSE|UNKNOWN}. */
public record IsBoolPredicate(Expression value, BoolTest test, boolean negated, SourcePosition pos)
        implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitIsBoolPredicate(this);
    }
}
