package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/** [WITH …] UPDATE ... SET ... [FROM ...] [WHERE ...]. */
public record UpdateStatement(List<Cte> ctes, boolean recursive, QualifiedName table,
                              Optional<Identifier> alias, List<Assignment> assignments,
                              Optional<TableSource> from, Optional<Expression> where,
                              SourcePosition pos)
        implements Statement {

    public UpdateStatement {
        ctes = List.copyOf(ctes);
        alias = alias != null ? alias : Optional.empty();
        assignments = List.copyOf(assignments);
        from = from != null ? from : Optional.empty();
        if (ctes.isEmpty() && recursive) {
            throw new IllegalArgumentException("recursive=true requires a non-empty CTE list");
        }
    }

    /** No WITH clause — keeps existing call sites unchanged. */
    public UpdateStatement(QualifiedName table, Optional<Identifier> alias,
                           List<Assignment> assignments, Optional<TableSource> from,
                           Optional<Expression> where, SourcePosition pos) {
        this(List.of(), false, table, alias, assignments, from, where, pos);
    }

    /** Compact form — no alias, no FROM, no WITH. */
    public UpdateStatement(QualifiedName table, List<Assignment> assignments,
                           Optional<Expression> where, SourcePosition pos) {
        this(List.of(), false, table, Optional.empty(), assignments, Optional.empty(), where, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUpdateStatement(this);
    }
}
