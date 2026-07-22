package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * One common-table-expression: {@code name [(cols)] AS (query)}.
 */
public record Cte(Identifier name, Optional<List<Identifier>> columns, Query query,
                  SourcePosition pos) implements AstNode {

    public Cte {
        columns = columns.map(List::copyOf);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCte(this);
    }
}
