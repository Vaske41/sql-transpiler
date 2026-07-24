package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;

/**
 * One reusable query node (D7), shared by statements and subqueries. The flat union
 * list mirrors the grammar: {@code withClause? querySpecification (UNION ALL?
 * querySpecification)* orderByClause? rowLimitClause?}.
 *
 * {@code recursive} is true when the WITH clause carried {@code RECURSIVE} or any
 * CTE body self-references its own name (T-SQL-style recursion without the keyword).
 * Printers emit {@code WITH RECURSIVE} for PG/MySQL when set; T-SQL drops the keyword.
 */
public record Query(List<Cte> ctes, boolean recursive, QuerySpecification first,
                    List<UnionArm> unionArms, List<OrderItem> orderBy, Optional<RowLimit> limit,
                    SourcePosition pos) implements AstNode {

    public Query {
        ctes = List.copyOf(ctes);
        unionArms = List.copyOf(unionArms);
        orderBy = List.copyOf(orderBy);
        if (ctes.isEmpty() && recursive) {
            throw new IllegalArgumentException("recursive=true requires a non-empty CTE list");
        }
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitQuery(this);
    }
}
