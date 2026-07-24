package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/**
 * ALTER TABLE … ALTER/MODIFY COLUMN … type change — one AST for PG {@code TYPE}/
 * {@code SET DATA TYPE}, MySQL {@code MODIFY}, and T-SQL {@code ALTER COLUMN c type}.
 */
public record AlterColumnType(Identifier column, DataType type, Optional<Expression> using,
                              SourcePosition pos)
        implements AlterAction {

    public AlterColumnType {
        using = using != null ? using : Optional.empty();
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAlterColumnType(this);
    }
}
