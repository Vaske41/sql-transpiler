package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** Row constructor {@code (e1, e2, …)} — used e.g. in {@code (a,b) IN (SELECT…)}. */
public record RowConstructor(List<Expression> elements, SourcePosition pos)
        implements Expression {

    public RowConstructor {
        elements = List.copyOf(elements);
        if (elements.size() < 2) {
            throw new IllegalArgumentException("row constructor needs at least two elements");
        }
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitRowConstructor(this);
    }
}
