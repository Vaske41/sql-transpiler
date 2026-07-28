package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.ExistsPredicate;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.InSubqueryPredicate;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.RowConstructor;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.SubqueryExpression;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Row-shaped rewrites: {@code (x,y) IN (SELECT …)} → {@code EXISTS …} toward T-SQL,
 * and multi-column {@code SET (a,b) = (SELECT x,y …)} → per-column scalar subqueries
 * when the RHS is guaranteed single-row.
 */
public final class RewriteRowConstructorRule implements Rule {

    @Override
    public String name() {
        return "rewrite-row-constructor";
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
        public Object visitInSubqueryPredicate(InSubqueryPredicate node) {
            InSubqueryPredicate rebuilt = (InSubqueryPredicate) super.visitInSubqueryPredicate(node);
            if (ctx.target() != Dialect.TSQL) {
                return rebuilt;
            }
            if (rebuilt.negated()) {
                if (rebuilt.value() instanceof RowConstructor) {
                    throw new UnsupportedFeatureException(
                            "row constructor NOT IN to T-SQL", rebuilt.pos());
                }
                return rebuilt;
            }
            if (!(rebuilt.value() instanceof RowConstructor row)) {
                return rebuilt;
            }
            return toExists(row, rebuilt.subquery(), rebuilt.pos());
        }

        @Override
        public Object visitUpdateStatement(UpdateStatement node) {
            UpdateStatement updated = (UpdateStatement) super.visitUpdateStatement(node);
            if (ctx.target() == Dialect.POSTGRESQL) {
                return updated;
            }
            List<Assignment> expanded = new ArrayList<>();
            for (Assignment assignment : updated.assignments()) {
                expanded.addAll(expandMultiColumnSet(assignment));
            }
            if (expanded.equals(updated.assignments())) {
                return updated;
            }
            return new UpdateStatement(updated.ctes(), updated.recursive(), updated.table(),
                    updated.alias(), List.copyOf(expanded), updated.outputClause(),
                    updated.from(), updated.where(), updated.pos());
        }

        private List<Assignment> expandMultiColumnSet(Assignment assignment) {
            if (assignment.columns().size() <= 1) {
                return List.of(assignment);
            }
            Expression value = assignment.value();
            if (value instanceof RowConstructor row) {
                if (row.elements().size() != assignment.columns().size()) {
                    throw new UnsupportedFeatureException(
                            "multi-column SET arity mismatch", assignment.pos());
                }
                List<Assignment> out = new ArrayList<>(assignment.columns().size());
                for (int i = 0; i < assignment.columns().size(); i++) {
                    out.add(new Assignment(assignment.columns().get(i),
                            row.elements().get(i), assignment.pos()));
                }
                return out;
            }
            if (!(value instanceof SubqueryExpression sub)) {
                throw new UnsupportedFeatureException(
                        "multi-column SET assignment is not supported by " + ctx.target(),
                        assignment.pos());
            }
            if (!isGuaranteedSingleRow(sub.query())) {
                throw new UnsupportedFeatureException(
                        "multi-column SET from uncorrelated subquery", assignment.pos());
            }
            Query query = sub.query();
            QuerySpecification spec = query.first();
            if (spec.items().size() != assignment.columns().size()) {
                throw new UnsupportedFeatureException(
                        "multi-column SET select arity mismatch", assignment.pos());
            }
            List<Assignment> out = new ArrayList<>(assignment.columns().size());
            for (int i = 0; i < assignment.columns().size(); i++) {
                SelectItem item = spec.items().get(i);
                if (!(item instanceof SelectExpr selectExpr)) {
                    throw new UnsupportedFeatureException(
                            "multi-column SET requires scalar select items", assignment.pos());
                }
                QuerySpecification scalarSpec = new QuerySpecification(
                        spec.quantifier(),
                        spec.distinctOn(),
                        List.of(new SelectExpr(selectExpr.expr(), Optional.empty(), spec.pos())),
                        spec.from(),
                        spec.where(),
                        spec.groupBy(),
                        spec.groupByModifier(),
                        spec.having(),
                        spec.pos());
                Query scalarQuery = new Query(
                        query.ctes(), query.recursive(), scalarSpec,
                        query.unionArms(), query.orderBy(), query.limit(), query.pos());
                out.add(new Assignment(
                        List.of(assignment.columns().get(i)),
                        new SubqueryExpression(scalarQuery, assignment.pos()),
                        assignment.pos()));
            }
            return out;
        }

        private static boolean isGuaranteedSingleRow(Query query) {
            if (!query.unionArms().isEmpty() || !query.orderBy().isEmpty()) {
                return false;
            }
            return query.limit()
                    .flatMap(RowLimit::count)
                    .filter(c -> c instanceof NumericLiteral n && "1".equals(n.text()))
                    .isPresent();
        }

        private static ExistsPredicate toExists(RowConstructor row, Query subquery,
                                                SourcePosition pos) {
            Query inner = subquery;
            if (!inner.orderBy().isEmpty() || inner.limit().isPresent()) {
                throw new UnsupportedFeatureException(
                        "row constructor IN subquery with ORDER BY or LIMIT", pos);
            }
            if (!inner.unionArms().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "row constructor IN subquery with set operations", pos);
            }
            QuerySpecification spec = inner.first();
            if (spec.items().size() != row.elements().size()) {
                throw new UnsupportedFeatureException(
                        "row constructor arity mismatch in IN subquery", pos);
            }
            Expression where = null;
            for (int i = 0; i < row.elements().size(); i++) {
                SelectItem item = spec.items().get(i);
                if (!(item instanceof SelectExpr selectExpr)) {
                    throw new UnsupportedFeatureException(
                            "row constructor IN requires scalar select items", pos);
                }
                BinaryOp eq = new BinaryOp(BinaryOperator.EQ, selectExpr.expr(),
                        row.elements().get(i), pos);
                where = where == null ? eq : new BinaryOp(BinaryOperator.AND, where, eq, pos);
            }
            if (spec.where().isPresent()) {
                where = new BinaryOp(BinaryOperator.AND, spec.where().get(), where, pos);
            }
            List<SelectItem> existsItems = List.of(new SelectExpr(
                    new NumericLiteral("1", false, pos), Optional.empty(), pos));
            QuerySpecification existsSpec = new QuerySpecification(
                    Optional.empty(),
                    List.of(),
                    existsItems,
                    spec.from(),
                    Optional.of(where),
                    spec.groupBy(),
                    spec.groupByModifier(),
                    spec.having(),
                    pos);
            Query existsQuery = new Query(
                    inner.ctes(), inner.recursive(), existsSpec,
                    List.of(), List.of(), Optional.empty(), pos);
            return new ExistsPredicate(existsQuery, pos);
        }
    }
}
