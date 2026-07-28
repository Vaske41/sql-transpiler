package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** MySQL {@code SET @var = expr} session user-variable assignment. */
public record SetUserVariableStatement(Identifier variable, Expression value,
                                       SourcePosition pos) implements Statement {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitSetUserVariableStatement(this);
    }
}
