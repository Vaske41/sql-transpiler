package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.DerivedTable;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JsonTableRelation;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Relation;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.SelectStar;
import rs.etf.sqltranslator.ast.TableFunction;
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
 * {@code ON left.col = right.col AND …}.
 *
 * <p>For a <em>chain</em> of USING joins, the left side of each subsequent ON is the
 * accumulated join result (nested as a derived table), not merely the previous right
 * relation — otherwise {@code c JOIN g USING (gid) JOIN u USING (uid)} wrongly emits
 * {@code g.uid = u.uid} when {@code uid} lives on {@code c}.
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
            Relation leftRel = rebuild(node.first());
            List<Join> resultJoins = new ArrayList<>();
            int nestId = 0;
            for (Join join : node.joins()) {
                Join rebuilt = (Join) rebuild(join);
                if (rebuilt.usingColumns().isEmpty()) {
                    resultJoins.add(rebuilt);
                    continue;
                }
                if (!resultJoins.isEmpty()) {
                    // Nest accumulated left so USING columns resolve against the join result.
                    DerivedTable nest = wrapAccumulated(
                            leftRel, resultJoins, "_using" + nestId++, rebuilt.pos());
                    leftRel = nest;
                    resultJoins = new ArrayList<>();
                }
                String leftName = relationName(leftRel, rebuilt.pos());
                String rightName = relationName(rebuilt.table(), rebuilt.pos());
                Expression on = usingEquals(leftName, rightName, rebuilt.usingColumns(),
                        rebuilt.pos());
                resultJoins.add(new Join(rebuilt.kind(), rebuilt.table(), Optional.of(on),
                        List.of(), rebuilt.lateral(), rebuilt.pos()));
            }
            return new TableSource(leftRel, resultJoins, node.pos());
        }

        private static DerivedTable wrapAccumulated(
                Relation first, List<Join> joins, String alias, SourcePosition pos) {
            TableSource from = new TableSource(first, joins, pos);
            QuerySpecification spec = new QuerySpecification(
                    Optional.empty(),
                    List.<SelectItem>of(new SelectStar(Optional.empty(), pos)),
                    Optional.of(from),
                    Optional.empty(),
                    List.of(),
                    Optional.empty(),
                    pos);
            Query query = new Query(
                    List.of(), false, spec, List.of(), List.of(), Optional.empty(), pos);
            return new DerivedTable(
                    query,
                    new Identifier(alias, false, pos),
                    Optional.empty(),
                    pos);
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
            if (relation instanceof TableFunction fn) {
                return fn.alias().orElse(fn.name().last()).value();
            }
            if (relation instanceof JsonTableRelation jt) {
                return jt.alias().map(Identifier::value).orElseThrow(() ->
                        new UnsupportedFeatureException(
                                "JOIN USING requires a named left/right relation for T-SQL expansion",
                                pos));
            }
            throw new UnsupportedFeatureException(
                    "JOIN USING requires a named left/right relation for T-SQL expansion",
                    pos);
        }
    }
}
