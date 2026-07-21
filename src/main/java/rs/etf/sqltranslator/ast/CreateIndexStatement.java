package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/**
 * CREATE [UNIQUE] INDEX name ON table (col [DESC], ...) — one canonical shape for
 * all three dialects; dialect-only options (CLUSTERED, USING, partial WHERE, NULLS,
 * prefix lengths) are refused at build, never recorded.
 */
public record CreateIndexStatement(Identifier name, boolean unique, QualifiedName table,
                                   List<IndexColumn> columns, SourcePosition pos)
        implements Statement {

    public CreateIndexStatement {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("an index needs at least one column");
        }
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCreateIndexStatement(this);
    }
}
