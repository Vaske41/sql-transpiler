package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/** {@code CREATE [OR REPLACE] FUNCTION|PROCEDURE … AS body}. */
public record CreateRoutineStatement(RoutineKind kind, QualifiedName name,
                                     List<ColumnDefinition> params, Optional<DataType> returns,
                                     List<Statement> body, SourcePosition pos)
        implements Statement {

    public CreateRoutineStatement {
        params = List.copyOf(params);
        body = List.copyOf(body);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCreateRoutineStatement(this);
    }

    public enum RoutineKind {
        FUNCTION, PROCEDURE
    }
}
