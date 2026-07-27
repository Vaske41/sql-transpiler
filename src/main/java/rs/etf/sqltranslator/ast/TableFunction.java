package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * Set-returning / table function in FROM/JOIN position:
 * {@code generate_series(...) AS g}, {@code json_array_elements(x) tt}, etc.
 * Optional typed column definition lists ({@code AS rec(circuitid INT, name TEXT)})
 * are carried in {@code columnTypes}; bare name lists stay in {@code columnAliases}.
 */
public record TableFunction(QualifiedName name, List<Expression> args,
                            Optional<Identifier> alias,
                            Optional<List<Identifier>> columnAliases,
                            List<ColumnDefinition> columnTypes,
                            SourcePosition pos) implements Relation {

    public TableFunction {
        args = List.copyOf(args);
        columnAliases = columnAliases.map(List::copyOf);
        columnTypes = List.copyOf(columnTypes);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitTableFunction(this);
    }
}
