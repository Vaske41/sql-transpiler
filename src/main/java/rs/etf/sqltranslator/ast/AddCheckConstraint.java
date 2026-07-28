package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** {@code ALTER TABLE … ADD [CONSTRAINT …] CHECK (…)}. */
public record AddCheckConstraint(Optional<Identifier> name, Expression predicate,
                                 SourcePosition pos) implements AlterAction {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAddCheckConstraint(this);
    }
}
