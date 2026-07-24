package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * Set-returning / table function in FROM/JOIN position:
 * {@code generate_series(...) AS g}, {@code json_array_elements(x) tt}, etc.
 */
public record TableFunction(QualifiedName name, List<Expression> args,
                            Optional<Identifier> alias,
                            Optional<List<Identifier>> columnAliases,
                            SourcePosition pos) implements Relation {

    public TableFunction {
        args = List.copyOf(args);
        columnAliases = columnAliases.map(List::copyOf);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitTableFunction(this);
    }
}
