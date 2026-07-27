package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * MySQL {@code JSON_TABLE(source, path COLUMNS (...))} relation produced by
 * {@code RenderSrfForMysqlRule} from PostgreSQL set-returning functions.
 * Column PATH clauses are derived at print time: array paths ({@code $[*]}) use
 * {@code PATH '$'}; object-root paths ({@code $}) use {@code PATH '$.col'}.
 */
public record JsonTableRelation(Expression source, String path,
                                List<ColumnDefinition> columns,
                                Optional<Identifier> alias,
                                SourcePosition pos) implements Relation {

    public JsonTableRelation {
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("JSON_TABLE requires at least one column");
        }
    }

    /**
     * {@code json_array_elements} / {@code jsonb_array_elements} →
     * {@code JSON_TABLE(x, '$[*]' COLUMNS (value JSON PATH '$'))}.
     */
    public static JsonTableRelation arrayElements(Expression source,
                                                  Optional<Identifier> alias,
                                                  SourcePosition pos) {
        return arrayElements(source, alias, pos, false);
    }

    /**
     * {@code *_text} variants use a TEXT column so MySQL yields unquoted scalars
     * (equivalent to {@code JSON_UNQUOTE} over a JSON column).
     */
    public static JsonTableRelation arrayElements(Expression source,
                                                  Optional<Identifier> alias,
                                                  SourcePosition pos,
                                                  boolean asText) {
        DataType type = new DataType(
                asText ? GenericType.TEXT : GenericType.JSON,
                Optional.empty(), Optional.empty());
        ColumnDefinition value = new ColumnDefinition(
                new Identifier("value", false, pos), type,
                false, Optional.empty(), Optional.empty(),
                false, false, Optional.empty(), pos);
        return new JsonTableRelation(source, "$[*]", List.of(value), alias, pos);
    }

    /**
     * {@code jsonb_to_record(x) AS alias(col type, …)} →
     * {@code JSON_TABLE(x, '$' COLUMNS (col type PATH '$.col', …))}.
     */
    public static JsonTableRelation objectFields(Expression source,
                                                 List<ColumnDefinition> columns,
                                                 Optional<Identifier> alias,
                                                 SourcePosition pos) {
        return new JsonTableRelation(source, "$", columns, alias, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitJsonTableRelation(this);
    }
}
