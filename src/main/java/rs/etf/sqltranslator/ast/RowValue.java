package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** One parenthesized row of a {@code VALUES} list: {@code (e1, e2, …)}. */
public record RowValue(List<Expression> values, SourcePosition pos) implements AstNode {

    public RowValue {
        values = List.copyOf(values);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitRowValue(this);
    }
}
