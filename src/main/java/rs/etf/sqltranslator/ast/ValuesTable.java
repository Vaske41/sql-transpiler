package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/**
 * {@code (VALUES (…), (…)) AS alias} or {@code (VALUES …) AS alias(c1, c2)}.
 * An empty {@code columns} list means no column-alias list was written.
 */
public record ValuesTable(List<RowValue> rows, Identifier alias, List<Identifier> columns,
                          SourcePosition pos) implements Relation {

    public ValuesTable {
        rows = List.copyOf(rows);
        columns = List.copyOf(columns);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("VALUES table requires at least one row");
        }
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitValuesTable(this);
    }
}
