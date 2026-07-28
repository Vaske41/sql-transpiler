package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

/** MySQL {@code @var := expr} user-variable assignment (expression position). */
public record UserVarAssignment(Identifier variable, Expression value,
                                SourcePosition pos) implements Expression {

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUserVarAssignment(this);
    }
}
