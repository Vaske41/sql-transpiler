package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.DerivedTable;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Relation;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.ValuesTable;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * T-SQL has no {@code JOIN … USING (…)}. Expand each USING join to an equivalent
 * {@code ON left.col = right.col AND …} using the immediate left and right relation
 * aliases (or bare table names). Refuses when a side has no resolvable name.
 */
public final class ExpandJoinUsingForTsqlRule implements Rule {

    @Override
    public String name() {
        return "expand-join-using-for-tsql";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.TSQL) {
            return script;
        }
        return new Rewriter().transform(script);
    }

    private static final class Rewriter extends AstTransformer {

        @Override
        public Object visitTableSource(TableSource node) {
            Relation first = rebuild(node.first());
            String leftName = relationName(first, node.pos());
            List<Join> joins = new ArrayList<>();
            for (Join join : node.joins()) {
                Join rebuilt = (Join) rebuild(join);
                if (rebuilt.usingColumns().isEmpty()) {
                    joins.add(rebuilt);
                    leftName = relationName(rebuilt.table(), rebuilt.pos());
                    continue;
                }
                String rightName = relationName(rebuilt.table(), rebuilt.pos());
                Expression on = usingEquals(leftName, rightName, rebuilt.usingColumns(),
                        rebuilt.pos());
                joins.add(new Join(rebuilt.kind(), rebuilt.table(), Optional.of(on),
                        List.of(), rebuilt.lateral(), rebuilt.pos()));
                leftName = rightName;
            }
            return new TableSource(first, joins, node.pos());
        }

        private static Expression usingEquals(
                String left, String right, List<Identifier> cols, SourcePosition pos) {
            Expression expr = null;
            for (Identifier col : cols) {
                ColumnRef l = new ColumnRef(new QualifiedName(
                        List.of(new Identifier(left, false, pos), col), pos), pos);
                ColumnRef r = new ColumnRef(new QualifiedName(
                        List.of(new Identifier(right, false, pos), col), pos), pos);
                BinaryOp eq = new BinaryOp(BinaryOperator.EQ, l, r, pos);
                expr = expr == null ? eq
                        : new BinaryOp(BinaryOperator.AND, expr, eq, pos);
            }
            if (expr == null) {
                throw new UnsupportedFeatureException("JOIN USING with empty column list", pos);
            }
            return expr;
        }

        private static String relationName(Relation relation, SourcePosition pos) {
            if (relation instanceof TableRef ref) {
                return ref.alias().orElse(ref.table().last()).value();
            }
            if (relation instanceof DerivedTable derived) {
                return derived.alias().value();
            }
            if (relation instanceof ValuesTable values) {
                return values.alias().value();
            }
            throw new UnsupportedFeatureException(
                    "JOIN USING requires a named left/right relation for T-SQL expansion",
                    pos);
        }
    }
}
