package rs.etf.sqltranslator.ast;

import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;

/**
 * Extended {@code GROUP BY} forms: {@code ROLLUP}, {@code CUBE}, or
 * {@code GROUPING SETS}. Plain {@code GROUP BY col, …} uses {@link GroupByKind#PLAIN}
 * with an empty {@code sets} list.
 */
public record GroupByModifier(GroupByKind kind, List<List<Expression>> sets, SourcePosition pos)
        implements AstNode {

    public GroupByModifier {
        sets = sets.stream().map(List::copyOf).toList();
    }

    public static GroupByModifier rollup(SourcePosition pos) {
        return new GroupByModifier(GroupByKind.ROLLUP, List.of(), pos);
    }

    public static GroupByModifier cube(SourcePosition pos) {
        return new GroupByModifier(GroupByKind.CUBE, List.of(), pos);
    }

    public static GroupByModifier groupingSets(List<List<Expression>> sets, SourcePosition pos) {
        return new GroupByModifier(GroupByKind.GROUPING_SETS, sets, pos);
    }

    @Override
    public <R> R accept(AstVisitor<R> visitor) {
        return visitor.visitGroupByModifier(this);
    }
}
