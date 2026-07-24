package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.DerivedTable;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.OrderItem;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.SelectStar;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.WindowSpec;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rewrite PostgreSQL {@code SELECT DISTINCT ON (k) … ORDER BY k, s} for MySQL / T-SQL as
 * {@code SELECT cols FROM (SELECT cols, ROW_NUMBER() OVER (PARTITION BY k ORDER BY s) AS _rn
 * FROM …) _t WHERE _rn = 1}. Refuse {@code DISTINCT ON} without {@code ORDER BY} (no faithful
 * single-row pick) and {@code DISTINCT ON} with {@code SELECT *} (outer star would leak
 * {@code _rn}; expansion needs a catalog). Leave native {@code DISTINCT ON} when the target
 * is PostgreSQL.
 */
public final class RewriteDistinctOnRule implements Rule {

    private static final String RN_ALIAS = "_rn";
    private static final String WRAP_ALIAS = "_t";

    @Override
    public String name() {
        return "rewrite-distinct-on";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Rewriter(ctx).transform(script);
    }

    private static final class Rewriter extends AstTransformer {

        private final TranslationContext ctx;

        private Rewriter(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitQuery(Query node) {
            Query rebuilt = (Query) super.visitQuery(node);
            if (rebuilt.first().distinctOn().isEmpty()) {
                return rebuilt;
            }
            if (ctx.target() == Dialect.POSTGRESQL) {
                return rebuilt;
            }
            if (rebuilt.orderBy().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "DISTINCT ON without ORDER BY", rebuilt.first().pos());
            }
            if (containsSelectStar(rebuilt.first().items())) {
                // Outer SELECT * over the wrap would project _rn. Expansion needs a
                // catalog; without it, refuse rather than emit a wrong column set.
                throw new UnsupportedFeatureException(
                        "DISTINCT ON with SELECT *", rebuilt.first().pos());
            }
            return rewrite(rebuilt);
        }

        private static boolean containsSelectStar(List<SelectItem> items) {
            return items.stream().anyMatch(SelectStar.class::isInstance);
        }

        private Query rewrite(Query query) {
            QuerySpecification spec = query.first();
            SourcePosition pos = spec.pos();
            List<SelectItem> innerItems = new ArrayList<>(ensureAliases(spec.items(), pos));
            Identifier rnId = new Identifier(RN_ALIAS, false, pos);
            FunctionCall rowNumber = new FunctionCall(
                    "ROW_NUMBER",
                    List.of(),
                    false,
                    Optional.empty(),
                    Optional.of(new WindowSpec(
                            List.copyOf(spec.distinctOn()),
                            List.copyOf(query.orderBy()),
                            Optional.empty(),
                            pos)),
                    pos);
            innerItems.add(new SelectExpr(rowNumber, Optional.of(rnId), pos));

            QuerySpecification innerSpec = new QuerySpecification(
                    Optional.empty(),
                    List.of(),
                    innerItems,
                    spec.from(),
                    spec.where(),
                    spec.groupBy(),
                    spec.having(),
                    pos);
            Query innerQuery = new Query(
                    List.of(),
                    false,
                    innerSpec,
                    List.of(),
                    List.of(),
                    Optional.empty(),
                    pos);

            Identifier wrapId = new Identifier(WRAP_ALIAS, false, pos);
            DerivedTable derived = new DerivedTable(
                    innerQuery, wrapId, Optional.empty(), pos);
            Expression rnEqOne = new BinaryOp(
                    BinaryOperator.EQ,
                    new ColumnRef(new QualifiedName(List.of(rnId), pos), pos),
                    new NumericLiteral("1", false, pos),
                    pos);

            QuerySpecification outerSpec = new QuerySpecification(
                    Optional.empty(),
                    List.of(),
                    outerItems(innerItems, pos),
                    Optional.of(new TableSource(derived, List.of(), pos)),
                    Optional.of(rnEqOne),
                    List.of(),
                    Optional.empty(),
                    pos);

            return new Query(
                    query.ctes(),
                    query.recursive(),
                    outerSpec,
                    query.unionArms(),
                    unwrapOrder(query.orderBy(), pos),
                    query.limit(),
                    query.pos());
        }

        /**
         * Ensure every non-star select item has an alias so the outer query can project
         * by name (and so expressions are not duplicated).
         */
        private static List<SelectItem> ensureAliases(List<SelectItem> items, SourcePosition pos) {
            List<SelectItem> out = new ArrayList<>(items.size());
            int i = 0;
            for (SelectItem item : items) {
                SelectExpr expr = (SelectExpr) item;
                if (expr.alias().isPresent()) {
                    out.add(expr);
                    continue;
                }
                Identifier alias = defaultAlias(expr.expr(), i++, pos);
                out.add(new SelectExpr(expr.expr(), Optional.of(alias), expr.pos()));
            }
            return out;
        }

        private static Identifier defaultAlias(Expression expr, int index, SourcePosition pos) {
            if (expr instanceof ColumnRef ref) {
                Identifier last = ref.name().last();
                return new Identifier(last.value(), last.quoted(), pos);
            }
            return new Identifier("_c" + index, false, pos);
        }

        /** Outer projection: all inner items except the trailing {@code _rn}. */
        private static List<SelectItem> outerItems(List<SelectItem> innerItems, SourcePosition pos) {
            List<SelectItem> projected = new ArrayList<>();
            for (int i = 0; i < innerItems.size() - 1; i++) {
                SelectExpr expr = (SelectExpr) innerItems.get(i);
                Identifier alias = expr.alias().orElseThrow();
                projected.add(new SelectExpr(
                        new ColumnRef(new QualifiedName(List.of(alias), pos), pos),
                        Optional.of(alias),
                        pos));
            }
            return projected;
        }

        /** Drop table qualifiers so ORDER BY resolves against the wrap alias projection. */
        private static List<OrderItem> unwrapOrder(List<OrderItem> orderBy, SourcePosition pos) {
            List<OrderItem> out = new ArrayList<>(orderBy.size());
            for (OrderItem item : orderBy) {
                Expression expr = item.expr();
                if (expr instanceof ColumnRef ref && ref.name().parts().size() > 1) {
                    expr = new ColumnRef(new QualifiedName(List.of(ref.name().last()), pos), pos);
                }
                out.add(new OrderItem(expr, item.direction(), item.nulls(), item.pos()));
            }
            return List.copyOf(out);
        }
    }
}
