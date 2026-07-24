package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** {@code ALTER TABLE … ADD [CONSTRAINT …] PRIMARY KEY|UNIQUE|FOREIGN KEY …}. */
public record AddTableConstraint(TableConstraint constraint, SourcePosition pos)
        implements AlterAction {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAddTableConstraint(this);
    }
}
