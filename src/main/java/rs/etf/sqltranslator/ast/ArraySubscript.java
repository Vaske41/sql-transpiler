package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** PostgreSQL array subscript {@code base[index]} (1-based index in PostgreSQL). */
public record ArraySubscript(Expression base, Expression index, SourcePosition pos)
        implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitArraySubscript(this);
    }
}
