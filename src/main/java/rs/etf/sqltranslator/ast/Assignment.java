package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** One {@code [table.]column = value} or {@code (c1, c2, …) = value} assignment of an UPDATE. */
public record Assignment(List<QualifiedName> columns, Expression value, SourcePosition pos)
        implements AstNode {

    /** Single-column LHS — keeps existing call sites unchanged. */
    public Assignment(QualifiedName column, Expression value, SourcePosition pos) {
        this(List.of(column), value, pos);
    }

    /** Unqualified single column. */
    public Assignment(Identifier column, Expression value, SourcePosition pos) {
        this(new QualifiedName(List.of(column), column.pos()), value, pos);
    }

    /** Single-column LHS accessor — fails on multi-column assignments. */
    public QualifiedName column() {
        if (columns.size() != 1) {
            throw new IllegalStateException("single column() on multi-column assignment");
        }
        return columns.get(0);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAssignment(this);
    }
}
