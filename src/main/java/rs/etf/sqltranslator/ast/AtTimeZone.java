package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** PostgreSQL {@code expr AT TIME ZONE zone}. */
public record AtTimeZone(Expression value, Expression zone, SourcePosition pos)
        implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAtTimeZone(this);
    }
}
