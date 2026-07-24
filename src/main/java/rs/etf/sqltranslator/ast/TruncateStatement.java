package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** TRUNCATE [TABLE] name. */
public record TruncateStatement(QualifiedName table, SourcePosition pos) implements Statement {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitTruncateStatement(this);
    }
}
