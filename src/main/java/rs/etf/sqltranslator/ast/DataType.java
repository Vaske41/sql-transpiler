package rs.etf.sqltranslator.ast;

import java.util.Optional;

/**
 * A fully folded, dialect-agnostic type descriptor (D3). Deliberately carries no
 * {@code SourcePosition}: it is reused inside the {@code Catalog}, where positions are
 * meaningless — errors about types are reported at the owning
 * {@code ColumnDefinition}/{@code CastExpression} position.
 *
 * <p>{@code arrayDims} counts PostgreSQL {@code []} suffixes ({@code text[]} → 1,
 * {@code integer[][]} → 2). Non-PG targets refuse array types in validation.
 */
public record DataType(GenericType type, Optional<TypeLength> length, Optional<Integer> scale,
                       int arrayDims)
        implements AstNode {

    public DataType {
        if (arrayDims < 0) {
            throw new IllegalArgumentException("arrayDims must be >= 0");
        }
    }

    /** Non-array type (the common case). */
    public DataType(GenericType type, Optional<TypeLength> length, Optional<Integer> scale) {
        this(type, length, scale, 0);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitDataType(this);
    }
}
