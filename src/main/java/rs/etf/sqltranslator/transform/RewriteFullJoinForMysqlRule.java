package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.DerivedTable;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.JsonTableRelation;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Relation;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.SetOperator;
import rs.etf.sqltranslator.ast.TableFunction;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UnionArm;
import rs.etf.sqltranslator.ast.ValuesTable;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * MySQL has no {@code FULL [OUTER] JOIN}. Rewrite a single full join as
 * {@code LEFT JOIN … UNION ALL … RIGHT JOIN … WHERE left_key IS NULL}.
 *
 * <p>Refuses trailing joins after the FULL join, aggregates / {@code GROUP BY},
 * and ON predicates where the left-side anti-join key cannot be identified.
 */
public final class RewriteFullJoinForMysqlRule implements Rule {

    private static final Set<String> AGGREGATE_NAMES = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "ARRAY_AGG", "STRING_AGG",
            "GROUP_CONCAT", "JSON_ARRAYAGG", "JSON_OBJECTAGG", "BOOL_AND", "BOOL_OR",
            "BIT_AND", "BIT_OR", "STDDEV", "STDDEV_POP", "STDDEV_SAMP",
            "VARIANCE", "VAR_POP", "VAR_SAMP");

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
            if (site.index() < site.from().joins().size() - 1) {
                throw new UnsupportedFeatureException(
                        "FULL JOIN rewrite with joins after the FULL JOIN", site.join().pos());
            }
            if (!spec.groupBy().isEmpty() || spec.having().isPresent()
                    || containsAggregate(spec.items())) {
                throw new UnsupportedFeatureException(
                        "FULL JOIN rewrite with aggregation or GROUP BY", site.join().pos());
            }

            Set<String> leftAliases = leftAliases(site.from(), site.index());
            ColumnRef antiKey = extractAntiJoinKey(site.join().on().orElseThrow(), leftAliases)
                    .orElseThrow(() -> new UnsupportedFeatureException(
                            "FULL JOIN ON must be a simple equality on a left-side column",
                            site.join().pos()));

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

        private static Optional<ColumnRef> extractAntiJoinKey(Expression on,
                                                              Set<String> leftAliases) {
            if (!(on instanceof BinaryOp op) || op.op() != BinaryOperator.EQ) {
                return Optional.empty();
            }
            if (!(op.left() instanceof ColumnRef left) || !(op.right() instanceof ColumnRef right)) {
                return Optional.empty();
            }
            boolean leftOnLeft = qualifiesWith(left, leftAliases);
            boolean rightOnLeft = qualifiesWith(right, leftAliases);
            if (leftOnLeft && !rightOnLeft) {
                return Optional.of(left);
            }
            if (rightOnLeft && !leftOnLeft) {
                return Optional.of(right);
            }
            return Optional.empty();
        }

        private static boolean qualifiesWith(ColumnRef ref, Set<String> aliases) {
            if (ref.name().parts().size() < 2) {
                return false;
            }
            return aliases.contains(ref.name().parts().get(0).value().toLowerCase(Locale.ROOT));
        }

        private static Set<String> leftAliases(TableSource from, int fullIndex) {
            Set<String> aliases = new HashSet<>();
            addAlias(from.first(), aliases);
            for (int i = 0; i < fullIndex; i++) {
                addAlias(from.joins().get(i).table(), aliases);
            }
            return aliases;
        }

        private static void addAlias(Relation relation, Set<String> aliases) {
            if (relation instanceof TableRef ref) {
                aliases.add(ref.alias().orElse(ref.table().last()).value()
                        .toLowerCase(Locale.ROOT));
            } else if (relation instanceof DerivedTable derived) {
                aliases.add(derived.alias().value().toLowerCase(Locale.ROOT));
            } else if (relation instanceof ValuesTable values) {
                aliases.add(values.alias().value().toLowerCase(Locale.ROOT));
            } else if (relation instanceof TableFunction fn) {
                aliases.add(fn.alias().orElse(fn.name().last()).value()
                        .toLowerCase(Locale.ROOT));
            } else if (relation instanceof JsonTableRelation jt) {
                jt.alias().ifPresent(a -> aliases.add(a.value().toLowerCase(Locale.ROOT)));
            }
        }

        private static boolean containsAggregate(List<SelectItem> items) {
            for (SelectItem item : items) {
                if (item instanceof SelectExpr expr && containsAggregate(expr.expr())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean containsAggregate(Expression expr) {
            if (expr instanceof FunctionCall call
                    && AGGREGATE_NAMES.contains(call.name().toUpperCase(Locale.ROOT))) {
                return true;
            }
            if (expr instanceof BinaryOp op) {
                return containsAggregate(op.left()) || containsAggregate(op.right());
            }
            return false;
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
