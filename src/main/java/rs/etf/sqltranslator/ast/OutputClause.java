package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** PostgreSQL {@code RETURNING} or T-SQL {@code OUTPUT} projection list. */
public record OutputClause(List<SelectItem> items, SourcePosition pos) implements AstNode {

    public OutputClause {
        items = List.copyOf(items);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitOutputClause(this);
    }
}
