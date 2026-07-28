package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** Table-level CHECK constraint in CREATE TABLE. */
public record CheckConstraint(Optional<Identifier> name, Expression predicate,
                              SourcePosition pos) implements TableConstraint {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCheckConstraint(this);
    }
}
