package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * {@code OVER (…)} window specification: optional {@code PARTITION BY},
 * {@code ORDER BY}, and frame (frame refused at build in v1).
 */
public record WindowSpec(List<Expression> partitionBy, List<OrderItem> orderBy,
                         Optional<WindowFrame> frame, SourcePosition pos)
        implements AstNode {

    public WindowSpec {
        partitionBy = List.copyOf(partitionBy);
        orderBy = List.copyOf(orderBy);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitWindowSpec(this);
    }
}
