package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Optional;

/** DELETE FROM ... [AS alias] [USING ...] [WHERE ...]. */
public record DeleteStatement(QualifiedName table, Optional<Identifier> alias,
                              Optional<TableSource> usingClause, Optional<Expression> where,
                              SourcePosition pos) implements Statement {

    public DeleteStatement {
        alias = alias != null ? alias : Optional.empty();
        usingClause = usingClause != null ? usingClause : Optional.empty();
    }

    /** Compact form — no alias, no USING. Keeps existing call sites unchanged. */
    public DeleteStatement(QualifiedName table, Optional<Expression> where, SourcePosition pos) {
        this(table, Optional.empty(), Optional.empty(), where, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDeleteStatement(this);
    }
}
