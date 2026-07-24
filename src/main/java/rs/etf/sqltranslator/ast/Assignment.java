package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/** One {@code [table.]column = value} assignment of an UPDATE. */
public record Assignment(QualifiedName column, Expression value, SourcePosition pos)
        implements AstNode {

    /** Unqualified column — keeps existing call sites unchanged. */
    public Assignment(Identifier column, Expression value, SourcePosition pos) {
        this(new QualifiedName(List.of(column), column.pos()), value, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitAssignment(this);
    }
}
