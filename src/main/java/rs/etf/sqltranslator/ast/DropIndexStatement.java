package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** DROP INDEX [IF EXISTS] name [ON table]. */
public record DropIndexStatement(Identifier name, boolean ifExists, Optional<QualifiedName> table,
                                 SourcePosition pos)
        implements Statement {

    public DropIndexStatement {
        table = table != null ? table : Optional.empty();
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDropIndexStatement(this);
    }
}
