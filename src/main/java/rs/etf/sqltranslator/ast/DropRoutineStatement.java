package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * DROP FUNCTION [IF EXISTS] name [(arg types…)] [CASCADE].
 * {@code argTypes} empty + {@code hasSignature=false} means no parentheses;
 * empty + {@code hasSignature=true} means {@code ()}; non-empty is an overloaded form.
 */
public record DropRoutineStatement(QualifiedName name, boolean ifExists, boolean cascade,
                                   boolean hasSignature, List<DataType> argTypes, SourcePosition pos)
        implements Statement {

    public DropRoutineStatement {
        argTypes = List.copyOf(argTypes);
    }

    public Optional<List<DataType>> signature() {
        return hasSignature ? Optional.of(argTypes) : Optional.empty();
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDropRoutineStatement(this);
    }
}
