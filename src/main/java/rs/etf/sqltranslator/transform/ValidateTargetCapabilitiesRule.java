package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnDefinition;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FrameBound;
import rs.etf.sqltranslator.ast.FrameBoundKind;
import rs.etf.sqltranslator.ast.FrameMode;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.QuerySpecification;
import rs.etf.sqltranslator.ast.RowLimit;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
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
        public Object visitAssignment(Assignment node) {
            if (node.columns().size() > 1
                    && (ctx.target() == Dialect.MYSQL || ctx.target() == Dialect.TSQL)) {
                throw new UnsupportedFeatureException(
                        "multi-column SET assignment is not supported by " + ctx.target(),
                        node.pos());
            }
            return super.visitAssignment(node);
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            if ((ctx.target() == Dialect.MYSQL || ctx.target() == Dialect.TSQL)
                    && ARRAY_AGGREGATES.contains(node.name())) {
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
        public Object visitJoin(Join node) {
            if (node.kind() == JoinKind.FULL && ctx.target() == Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "FULL JOIN is not supported by MySQL", node.pos());
            }
            if (node.lateral() && ctx.target() == Dialect.TSQL
                    && !isTsqlApplyShape(node)) {
                throw new UnsupportedFeatureException(
                        "LATERAL join ON condition cannot fold to APPLY", node.pos());
            }
            return super.visitJoin(node);
        }

        @Override
        public Object visitRowConstructor(rs.etf.sqltranslator.ast.RowConstructor node) {
            if (ctx.target() == Dialect.TSQL) {
                throw new UnsupportedFeatureException(
                        "row constructor is not supported by T-SQL", node.pos());
            }
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
        public Object visitAtTimeZone(rs.etf.sqltranslator.ast.AtTimeZone node) {
            if (ctx.target() != Dialect.POSTGRESQL) {
                throw new UnsupportedFeatureException(
                        "AT TIME ZONE is not supported by " + ctx.target(), node.pos());
            }
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
        public Object visitQuery(Query node) {
            node.limit().ifPresent(limit -> validateLimit(node, limit));
            return super.visitQuery(node);
        }

        @Override
        public Object visitQuerySpecification(QuerySpecification node) {
            warnLooseGroupBy(node);
            return super.visitQuerySpecification(node);
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
