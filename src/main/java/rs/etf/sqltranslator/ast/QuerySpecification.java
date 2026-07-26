package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * One SELECT block: items, FROM, WHERE, GROUP BY, HAVING.
 * {@code distinctOn} holds PostgreSQL {@code DISTINCT ON (…)} expressions when present;
 * plain {@code DISTINCT}/{@code ALL} live in {@code quantifier} alone.
 */
public record QuerySpecification(Optional<SetQuantifier> quantifier,
                                 List<Expression> distinctOn,
                                 List<SelectItem> items,
                                 Optional<TableSource> from, Optional<Expression> where,
                                 List<Expression> groupBy, Optional<Expression> having,
                                 SourcePosition pos) implements AstNode {

    public QuerySpecification {
        distinctOn = List.copyOf(distinctOn);
        items = List.copyOf(items);
        groupBy = List.copyOf(groupBy);
    }

    /** Convenience constructor — no {@code DISTINCT ON}. */
    public QuerySpecification(Optional<SetQuantifier> quantifier, List<SelectItem> items,
                              Optional<TableSource> from, Optional<Expression> where,
                              List<Expression> groupBy, Optional<Expression> having,
                              SourcePosition pos) {
        this(quantifier, List.of(), items, from, where, groupBy, having, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitQuerySpecification(this);
    }
}
