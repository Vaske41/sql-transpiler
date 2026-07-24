package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/** UPDATE ... SET ... [FROM ...] [WHERE ...]. */
public record UpdateStatement(QualifiedName table, Optional<Identifier> alias,
                              List<Assignment> assignments, Optional<TableSource> from,
                              Optional<Expression> where, SourcePosition pos)
        implements Statement {

    public UpdateStatement {
        alias = alias != null ? alias : Optional.empty();
        assignments = List.copyOf(assignments);
        from = from != null ? from : Optional.empty();
    }

    /** Compact form — no alias, no FROM. Keeps existing call sites unchanged. */
    public UpdateStatement(QualifiedName table, List<Assignment> assignments,
                           Optional<Expression> where, SourcePosition pos) {
        this(table, Optional.empty(), assignments, Optional.empty(), where, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitUpdateStatement(this);
    }
}
