package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** PostgreSQL {@code ARRAY[e1, e2, …]} literal. */
public record ArrayLiteral(List<Expression> elements, SourcePosition pos)
        implements Expression {

    public ArrayLiteral {
        elements = List.copyOf(elements);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitArrayLiteral(this);
    }
}
