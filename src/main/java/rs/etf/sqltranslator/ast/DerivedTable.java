package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * {@code (query) AS alias} or {@code (query) AS alias(c1, c2)}.
 * Column aliases are optional name lists only — no types.
 */
public record DerivedTable(Query query, Identifier alias,
                           Optional<List<Identifier>> columnAliases,
                           SourcePosition pos) implements Relation {

    public DerivedTable {
        columnAliases = columnAliases.map(List::copyOf);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDerivedTable(this);
    }
}
