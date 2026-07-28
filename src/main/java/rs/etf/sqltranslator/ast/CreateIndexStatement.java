package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * CREATE [UNIQUE] INDEX name ON table (key [DESC], ...) [INCLUDE (cols...)] [WHERE pred]
 * — one canonical shape for all three dialects; dialect-only options (CLUSTERED, USING,
 * NULLS, prefix lengths) are refused at build, never recorded.
 */
public record CreateIndexStatement(Identifier name, boolean unique, QualifiedName table,
                                   List<IndexColumn> columns, List<Identifier> includeColumns,
                                   Optional<Expression> where, SourcePosition pos)
        implements Statement {

    public CreateIndexStatement(Identifier name, boolean unique, QualifiedName table,
                                  List<IndexColumn> columns, SourcePosition pos) {
        this(name, unique, table, columns, List.of(), Optional.empty(), pos);
    }

    public CreateIndexStatement {
        columns = List.copyOf(columns);
        includeColumns = List.copyOf(includeColumns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("an index needs at least one column");
        }
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitCreateIndexStatement(this);
    }
}
