package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.Locale;
import java.util.Objects;

/**
 * {@code EXTRACT(field FROM source)}. {@code field} is uppercased at build time
 * (date-part names are case-insensitive in all three dialects).
 */
public record ExtractExpression(String field, Expression source, SourcePosition pos)
        implements Expression {

    public ExtractExpression {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(source, "source");
        field = field.toUpperCase(Locale.ROOT);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitExtractExpression(this);
    }
}
