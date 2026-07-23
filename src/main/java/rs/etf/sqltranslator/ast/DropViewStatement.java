package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** DROP VIEW [IF EXISTS] name [CASCADE]. */
public record DropViewStatement(QualifiedName name, boolean ifExists, boolean cascade, SourcePosition pos)
        implements Statement {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDropViewStatement(this);
    }
}
