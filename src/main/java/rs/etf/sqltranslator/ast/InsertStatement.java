package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * INSERT ... VALUES or INSERT ... SELECT; an empty {@code columns} list means no
 * column list was written. Exactly one source form: {@code rows} non-empty (VALUES)
 * xor {@code query} present (SELECT). Optional upsert ({@code ON CONFLICT} /
 * {@code ON DUPLICATE KEY}) and {@code RETURNING}/{@code OUTPUT} clause.
 */
public record InsertStatement(QualifiedName table, List<Identifier> columns,
                              List<List<Expression>> rows, Optional<Query> query,
                              Optional<Upsert> upsert,
                              Optional<OutputClause> outputClause,
                              SourcePosition pos)
        implements Statement {

    /** VALUES/SELECT insert with no upsert or output clause. */
    public InsertStatement(QualifiedName table, List<Identifier> columns,
                           List<List<Expression>> rows, Optional<Query> query,
                           SourcePosition pos) {
        this(table, columns, rows, query, Optional.empty(), Optional.empty(), pos);
    }

    public InsertStatement {
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
        if (query.isPresent() != rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "exactly one INSERT source: VALUES rows or a query");
        }
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitInsertStatement(this);
    }
}
