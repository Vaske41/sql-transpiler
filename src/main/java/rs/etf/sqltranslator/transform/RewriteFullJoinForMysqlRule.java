package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SetOperator;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UnionArm;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * MySQL has no {@code FULL [OUTER] JOIN}. Rewrite a single full join as
 * {@code LEFT JOIN … UNION ALL … RIGHT JOIN … WHERE left_key IS NULL}.
 */
public final class RewriteFullJoinForMysqlRule implements Rule {

    @Override
    public String name() {
        return "rewrite-full-join-mysql";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.MYSQL) {
            return script;
        }
        return new Rewriter().transform(script);
    }

    private static final class Rewriter extends AstTransformer {

        @Override
        public Object visitQuery(Query node) {
            Query rebuilt = (Query) super.visitQuery(node);
            if (!rebuilt.unionArms().isEmpty()) {
                if (containsFullJoin(rebuilt)) {
                    throw new UnsupportedFeatureException(
                            "FULL JOIN rewrite over set operations", rebuilt.pos());
                }
                return rebuilt;
            }
            if (!rebuilt.orderBy().isEmpty() || rebuilt.limit().isPresent()) {
                if (containsFullJoin(rebuilt)) {
                    throw new UnsupportedFeatureException(
                            "FULL JOIN rewrite with ORDER BY or LIMIT", rebuilt.pos());
                }
                return rebuilt;
            }
            QuerySpecification spec = rebuilt.first();
            if (spec.from().isEmpty() || locateFullJoin(spec.from().get()) == null) {
                return rebuilt;
            }
            return rewriteFullJoinQuery(rebuilt);
        }

        private static Query rewriteFullJoinQuery(Query query) {
            QuerySpecification spec = query.first();
            FullJoinSite site = locateFullJoin(spec.from().orElseThrow());
            ColumnRef antiKey = extractAntiJoinKey(site.join().on().orElseThrow())
                    .orElseThrow(() -> new UnsupportedFeatureException(
                            "FULL JOIN ON must be a simple equality", site.join().pos()));

            Join leftJoin = new Join(JoinKind.LEFT, site.join().table(), site.join().on(),
                    site.join().pos());
            Join rightJoin = new Join(JoinKind.RIGHT, site.join().table(), site.join().on(),
                    site.join().pos());

            TableSource leftFrom = new TableSource(
                    site.from().first(), concat(site.from().joins(), site.index(), leftJoin),
                    site.join().pos());
            QuerySpecification leftSpec = cloneSpec(spec, Optional.of(leftFrom), spec.where());

            Expression antiFilter = new IsNullPredicate(antiKey, false, site.join().pos());
            Optional<Expression> rightWhere;
            if (spec.where().isPresent()) {
                rightWhere = Optional.of(new BinaryOp(
                        BinaryOperator.AND, antiFilter, spec.where().get(), site.join().pos()));
            } else {
                rightWhere = Optional.of(antiFilter);
            }
            TableSource rightFrom = new TableSource(
                    site.from().first(), concat(site.from().joins(), site.index(), rightJoin),
                    site.join().pos());
            QuerySpecification rightSpec = cloneSpec(spec, Optional.of(rightFrom), rightWhere);

            return new Query(
                    query.ctes(),
                    query.recursive(),
                    leftSpec,
                    List.of(new UnionArm(SetOperator.UNION, true,
                            bareQuery(rightSpec, query.pos()), false, query.pos())),
                    List.of(),
                    Optional.empty(),
                    query.pos());
        }

        private static Query bareQuery(QuerySpecification spec, SourcePosition pos) {
            return new Query(List.of(), false, spec, List.of(), List.of(), Optional.empty(), pos);
        }

        private static QuerySpecification cloneSpec(QuerySpecification spec,
                                                    Optional<TableSource> from,
                                                    Optional<Expression> where) {
            return new QuerySpecification(
                    spec.quantifier(),
                    spec.distinctOn(),
                    spec.items(),
                    from,
                    where,
                    spec.groupBy(),
                    spec.groupByModifier(),
                    spec.having(),
                    spec.pos());
        }

        private static boolean containsFullJoin(Query query) {
            return query.unionArms().stream().anyMatch(a -> containsFullJoin(a.operand()))
                    || containsFullJoin(query.first());
        }

        private static boolean containsFullJoin(QuerySpecification spec) {
            return spec.from().map(from -> locateFullJoin(from) != null).orElse(false);
        }

        private static FullJoinSite locateFullJoin(TableSource from) {
            for (int i = 0; i < from.joins().size(); i++) {
                Join join = from.joins().get(i);
                if (join.kind() == JoinKind.FULL) {
                    if (!join.usingColumns().isEmpty()) {
                        throw new UnsupportedFeatureException(
                                "FULL JOIN USING is not supported by MySQL rewrite", join.pos());
                    }
                    if (join.on().isEmpty()) {
                        throw new UnsupportedFeatureException(
                                "FULL JOIN without ON is not supported by MySQL rewrite",
                                join.pos());
                    }
                    return new FullJoinSite(from, i, join);
                }
            }
            return null;
        }

        private static Optional<ColumnRef> extractAntiJoinKey(Expression on) {
            if (on instanceof BinaryOp op && op.op() == BinaryOperator.EQ) {
                if (op.left() instanceof ColumnRef left) {
                    return Optional.of(left);
                }
                if (op.right() instanceof ColumnRef right) {
                    return Optional.of(right);
                }
            }
            return Optional.empty();
        }

        private static List<Join> concat(List<Join> joins, int fullIndex, Join replacement) {
            List<Join> out = new ArrayList<>();
            for (int i = 0; i < fullIndex; i++) {
                out.add(joins.get(i));
            }
            out.add(replacement);
            return List.copyOf(out);
        }

        private record FullJoinSite(TableSource from, int index, Join join) {
        }
    }
}
