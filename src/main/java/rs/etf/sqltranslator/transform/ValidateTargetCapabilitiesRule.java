package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.CreateIndexStatement;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.DeleteStatement;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FrameBound;
import rs.etf.sqltranslator.ast.FrameBoundKind;
import rs.etf.sqltranslator.ast.FrameMode;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.GroupByKind;
import rs.etf.sqltranslator.ast.GroupByModifier;
import rs.etf.sqltranslator.ast.IndexColumn;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.AtTimeZone;
import rs.etf.sqltranslator.ast.RowConstructor;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.SetOperator;
import rs.etf.sqltranslator.ast.SetUserVariableStatement;
import rs.etf.sqltranslator.ast.UnionArm;
import rs.etf.sqltranslator.ast.UserVarAssignment;
import rs.etf.sqltranslator.ast.WindowFrame;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validate batch: refuse constructs the target cannot express, and warn when
 * MySQL loose {@code GROUP BY} would be rejected by PostgreSQL / T-SQL at
 * execution. Traverses via the identity transformer — fail fast on refusals,
 * never a silent wrong translation.
 */
public final class ValidateTargetCapabilitiesRule implements Rule {

    private static final Set<String> ARRAY_AGGREGATES =
            Set.of("ARRAY_AGG", "JSON_AGG", "JSONB_AGG");

    @Override
    public String name() {
        return "validate-target-capabilities";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Validator(ctx).transform(script);
    }

    private static final class Validator extends AstTransformer {

        private final TranslationContext ctx;

        private Validator(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitCreateIndexStatement(CreateIndexStatement node) {
            if (ctx.target() == Dialect.MYSQL) {
                if (node.where().isPresent()) {
                    throw new UnsupportedFeatureException("partial index", node.pos());
                }
                if (!node.includeColumns().isEmpty()) {
                    throw new UnsupportedFeatureException(
                            "INCLUDE columns in index", node.pos());
                }
            }
            if (ctx.target() == Dialect.TSQL) {
                for (IndexColumn key : node.columns()) {
                    if (!(key.key() instanceof ColumnRef)) {
                        throw new UnsupportedFeatureException(
                                "expression index key", node.pos());
                    }
                }
                node.where().ifPresent(where -> {
                    if (!isTsqlFilteredIndexPredicate(where)) {
                        throw new UnsupportedFeatureException(
                                "partial index predicate", node.pos());
                    }
                });
            }
            return super.visitCreateIndexStatement(node);
        }

        /** T-SQL filtered indexes allow boolean columns or simple comparisons to literals. */
        private static boolean isTsqlFilteredIndexPredicate(Expression where) {
            if (where instanceof ColumnRef) {
                return true;
            }
            return where instanceof BinaryOp;
        }

        @Override
        public Object visitAssignment(Assignment node) {
            return super.visitAssignment(node);
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            if (ctx.target() == Dialect.TSQL && ARRAY_AGGREGATES.contains(node.name())) {
                throw new UnsupportedFeatureException(
                        "aggregate " + node.name() + " (no array type in target)",
                        node.pos());
            }
            return super.visitFunctionCall(node);
        }

        @Override
        public Object visitCastExpression(CastExpression node) {
            refuseArrayType(node.targetType(), node.pos());
            return super.visitCastExpression(node);
        }

        @Override
        public Object visitColumnDefinition(ColumnDefinition node) {
            refuseArrayType(node.type(), node.pos());
            return super.visitColumnDefinition(node);
        }

        private void refuseArrayType(DataType type, SourcePosition pos) {
            if (type.arrayDims() > 0
                    && (ctx.target() == Dialect.MYSQL || ctx.target() == Dialect.TSQL)) {
                throw new UnsupportedFeatureException(
                        "array type (no array type in target)", pos);
            }
        }

        @Override
        public Object visitDeleteStatement(DeleteStatement node) {
            return super.visitDeleteStatement(node);
        }

        @Override
        public Object visitUnionArm(UnionArm node) {
            if (ctx.target() == Dialect.TSQL && node.all()
                    && (node.operator() == SetOperator.EXCEPT
                    || node.operator() == SetOperator.INTERSECT)) {
                throw new UnsupportedFeatureException(
                        node.operator() + " ALL is not supported by T-SQL", node.pos());
            }
            return super.visitUnionArm(node);
        }

        @Override
        public Object visitJoin(Join node) {
            if (node.lateral() && ctx.target() == Dialect.TSQL
                    && !isTsqlApplyShape(node)) {
                throw new UnsupportedFeatureException(
                        "LATERAL join ON condition cannot fold to APPLY", node.pos());
            }
            return super.visitJoin(node);
        }

        @Override
        public Object visitRowConstructor(RowConstructor node) {
            return super.visitRowConstructor(node);
        }

        @Override
        public Object visitArrayLiteral(rs.etf.sqltranslator.ast.ArrayLiteral node) {
            if (ctx.target() == Dialect.MYSQL || ctx.target() == Dialect.TSQL) {
                throw new UnsupportedFeatureException(
                        "ARRAY literal is not supported by " + ctx.target(), node.pos());
            }
            return super.visitArrayLiteral(node);
        }

        @Override
        public Object visitAtTimeZone(AtTimeZone node) {
            return super.visitAtTimeZone(node);
        }

        /**
         * SQL Server accepts {@code RANGE} only with {@code UNBOUNDED}/{@code CURRENT ROW}
         * extents. Offset bounds under {@code RANGE} are invalid T-SQL; {@code ROWS} frames
         * (and portable {@code RANGE UNBOUNDED…CURRENT ROW}) print structurally.
         */
        @Override
        public Object visitWindowFrame(WindowFrame node) {
            if (ctx.target() == Dialect.TSQL
                    && node.mode() == FrameMode.RANGE
                    && hasOffsetBound(node)) {
                throw new UnsupportedFeatureException(
                        "RANGE frame with offset bounds is not supported by T-SQL",
                        node.pos());
            }
            return super.visitWindowFrame(node);
        }

        private static boolean hasOffsetBound(WindowFrame frame) {
            if (isOffsetBound(frame.start())) {
                return true;
            }
            return frame.end().map(ValidateTargetCapabilitiesRule.Validator::isOffsetBound)
                    .orElse(false);
        }

        private static boolean isOffsetBound(FrameBound bound) {
            return bound.kind() == FrameBoundKind.PRECEDING
                    || bound.kind() == FrameBoundKind.FOLLOWING;
        }

        /** CROSS APPLY, or OUTER APPLY (= LEFT LATERAL with empty/TRUE ON). */
        private static boolean isTsqlApplyShape(Join node) {
            if (node.kind() == JoinKind.CROSS && node.on().isEmpty()) {
                return true;
            }
            if (node.kind() == JoinKind.LEFT) {
                return node.on().isEmpty()
                        || (node.on().get() instanceof BooleanLiteral b && b.value());
            }
            return false;
        }

        @Override
        public Object visitColumnRef(ColumnRef node) {
            if (ctx.target() != Dialect.MYSQL && isUserVariable(node)) {
                throw new UnsupportedFeatureException(
                        "user variable is not supported by the target", node.pos());
            }
            return super.visitColumnRef(node);
        }

        @Override
        public Object visitUserVarAssignment(UserVarAssignment node) {
            if (ctx.target() != Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "user-variable assignment is not supported by the target", node.pos());
            }
            return super.visitUserVarAssignment(node);
        }

        @Override
        public Object visitSetUserVariableStatement(SetUserVariableStatement node) {
            if (ctx.target() != Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "SET user variable is not supported by the target", node.pos());
            }
            return super.visitSetUserVariableStatement(node);
        }

        private static boolean isUserVariable(ColumnRef ref) {
            if (ref.name().parts().size() != 1) {
                return false;
            }
            String name = ref.name().last().value();
            return name.startsWith("@") && !name.startsWith("@@");
        }

        @Override
        public Object visitQuery(Query node) {
            node.limit().ifPresent(limit -> validateLimit(node, limit));
            return super.visitQuery(node);
        }

        @Override
        public Object visitQuerySpecification(QuerySpecification node) {
            node.groupByModifier().ifPresent(mod -> refuseGroupByModifier(mod));
            warnLooseGroupBy(node);
            return super.visitQuerySpecification(node);
        }

        private void refuseGroupByModifier(GroupByModifier mod) {
            if (ctx.target() != Dialect.MYSQL) {
                return;
            }
            if (mod.kind() == GroupByKind.CUBE || mod.kind() == GroupByKind.GROUPING_SETS) {
                throw new UnsupportedFeatureException(
                        mod.kind() + " is not supported by MySQL", mod.pos());
            }
        }

        private void warnLooseGroupBy(QuerySpecification spec) {
            if (ctx.source() != Dialect.MYSQL
                    || ctx.target() == Dialect.MYSQL
                    || spec.groupBy().isEmpty()) {
                return;
            }
            Set<String> grouped = spec.groupBy().stream()
                    .filter(ColumnRef.class::isInstance)
                    .map(e -> ((ColumnRef) e).name().last().value().toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            for (SelectItem item : spec.items()) {
                if (!(item instanceof SelectExpr selectExpr)) {
                    continue;
                }
                Expression expr = selectExpr.expr();
                if (!(expr instanceof ColumnRef ref)) {
                    continue;
                }
                String name = ref.name().last().value().toLowerCase(Locale.ROOT);
                if (!grouped.contains(name)) {
                    ctx.report().warn("LOOSE_GROUP_BY",
                            "MySQL allows select column '" + ref.name().last().value()
                                    + "' outside GROUP BY; " + ctx.target()
                                    + " will reject this at execution",
                            ref.pos());
                }
            }
        }

        private void validateLimit(Query query, RowLimit limit) {
            if (ctx.target() == Dialect.TSQL && !query.unionArms().isEmpty()
                    && query.orderBy().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "row limit over UNION requires ORDER BY on SQL Server",
                        limit.pos());
            }
            if (limit.withTies() && limit.offset().isPresent()
                    && ctx.target() == Dialect.TSQL) {
                throw new UnsupportedFeatureException(
                        "OFFSET/FETCH WITH TIES is not supported by T-SQL", limit.pos());
            }
            if (limit.withTies() && limit.offset().isPresent()
                    && ctx.target() == Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "LIMIT OFFSET WITH TIES is not supported by MySQL", limit.pos());
            }
            if (limit.offset().isEmpty()) {
                return;
            }
            if (ctx.target() == Dialect.TSQL && query.orderBy().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "OFFSET requires ORDER BY on SQL Server", limit.pos());
            }
            if (ctx.target() == Dialect.MYSQL && limit.count().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "OFFSET without LIMIT is not supported by MySQL", limit.pos());
            }
        }
    }
}
