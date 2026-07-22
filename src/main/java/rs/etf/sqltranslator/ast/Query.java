package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * One reusable query node (D7), shared by statements and subqueries. The flat union
 * list mirrors the grammar: {@code withClause? querySpecification (UNION ALL?
 * querySpecification)* orderByClause? rowLimitClause?}.
 */
public record Query(List<Cte> ctes, QuerySpecification first, List<UnionArm> unionArms,
                    List<OrderItem> orderBy, Optional<RowLimit> limit,
                    SourcePosition pos) implements AstNode {

    public Query {
        ctes = List.copyOf(ctes);
        unionArms = List.copyOf(unionArms);
        orderBy = List.copyOf(orderBy);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitQuery(this);
    }
}
