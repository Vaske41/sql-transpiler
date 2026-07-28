package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.CaseExpression;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.UnaryOp;
import rs.etf.sqltranslator.ast.Upsert;
import rs.etf.sqltranslator.ast.UpsertKind;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Faithful upsert reshaping:
 * <ul>
 *   <li>PostgreSQL {@code ON CONFLICT … DO UPDATE} → MySQL {@code ON DUPLICATE KEY UPDATE}
 *       when there is no UPDATE WHERE clause (MySQL cannot express it).</li>
 *   <li>{@code ON CONFLICT DO NOTHING} toward MySQL/T-SQL → refuse (no faithful form).</li>
 *   <li>Any upsert toward T-SQL → refuse (MERGE would need schema/keys we may not have).</li>
 *   <li>MySQL {@code ON DUPLICATE KEY} toward PostgreSQL → refuse (conflict target unknown).</li>
 *   <li>{@code RETURNING} toward MySQL/T-SQL → refuse.</li>
 * </ul>
 */
public final class RewriteUpsertRule implements Rule {

    @Override
    public String name() {
        return "rewrite-upsert";
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
        public Object visitInsertStatement(InsertStatement node) {
            InsertStatement rebuilt = (InsertStatement) super.visitInsertStatement(node);
            if (rebuilt.upsert().isEmpty()) {
                return rebuilt;
            }
            Upsert upsert = rebuilt.upsert().get();
            Optional<Upsert> reshaped = reshape(upsert);
            return new InsertStatement(rebuilt.table(), rebuilt.columns(), rebuilt.rows(),
                    rebuilt.query(), reshaped, rebuilt.outputClause(), rebuilt.pos());
        }

        private Optional<Upsert> reshape(Upsert upsert) {
            if (ctx.target() == Dialect.TSQL) {
                throw new UnsupportedFeatureException(
                        "INSERT upsert (ON CONFLICT / ON DUPLICATE KEY) is not supported by T-SQL",
                        upsert.pos());
            }
            return switch (upsert.kind()) {
                case ON_CONFLICT_UPDATE -> reshapeConflictUpdate(upsert);
                case ON_CONFLICT_NOTHING -> reshapeConflictNothing(upsert);
                case ON_DUPLICATE_KEY -> reshapeDuplicate(upsert);
            };
        }

        private Optional<Upsert> reshapeConflictUpdate(Upsert upsert) {
            if (ctx.target() == Dialect.POSTGRESQL) {
                return Optional.of(upsert);
            }
            // MySQL
            if (upsert.where().isPresent()) {
                throw new UnsupportedFeatureException(
                        "ON CONFLICT DO UPDATE WHERE is not supported by MySQL",
                        upsert.pos());
            }
            List<Assignment> assignments = new ArrayList<>(upsert.assignments().size());
            for (Assignment assignment : upsert.assignments()) {
                assignments.add(new Assignment(
                        assignment.columns(),
                        rewriteExcludedRefs(assignment.value(), assignment.pos()),
                        assignment.pos()));
            }
            return Optional.of(new Upsert(UpsertKind.ON_DUPLICATE_KEY, List.of(),
                    assignments, Optional.empty(), upsert.pos()));
        }

        /**
         * PostgreSQL {@code EXCLUDED.col} → MySQL {@code VALUES(col)} (proposed-row
         * reference in {@code ON DUPLICATE KEY UPDATE}). Exotic EXCLUDED shapes refuse.
         */
        private Expression rewriteExcludedRefs(Expression expr, SourcePosition pos) {
            if (expr instanceof ColumnRef ref) {
                return rewriteExcludedColumn(ref);
            }
            if (expr instanceof BinaryOp op) {
                return new BinaryOp(op.op(),
                        rewriteExcludedRefs(op.left(), pos),
                        rewriteExcludedRefs(op.right(), pos),
                        op.pos());
            }
            if (expr instanceof UnaryOp op) {
                return new UnaryOp(op.op(), rewriteExcludedRefs(op.operand(), pos), op.pos());
            }
            if (expr instanceof FunctionCall call) {
                List<Expression> args = new ArrayList<>(call.args().size());
                for (Expression arg : call.args()) {
                    args.add(rewriteExcludedRefs(arg, pos));
                }
                Optional<Expression> filter = call.filter()
                        .map(f -> rewriteExcludedRefs(f, pos));
                return new FunctionCall(call.name(), args, call.star(), call.quantifier(),
                        call.orderBy(), filter, call.window(), call.pos());
            }
            if (expr instanceof CastExpression cast) {
                return new CastExpression(rewriteExcludedRefs(cast.operand(), pos),
                        cast.targetType(), cast.pos());
            }
            if (expr instanceof CaseExpression caseExpr) {
                if (containsExcluded(caseExpr)) {
                    throw new UnsupportedFeatureException(
                            "EXCLUDED inside CASE has no faithful MySQL VALUES() mapping",
                            caseExpr.pos());
                }
                return caseExpr;
            }
            if (containsExcluded(expr)) {
                throw new UnsupportedFeatureException(
                        "EXCLUDED expression has no faithful MySQL VALUES() mapping",
                        pos);
            }
            return expr;
        }

        private Expression rewriteExcludedColumn(ColumnRef ref) {
            List<Identifier> parts = ref.name().parts();
            if (parts.isEmpty()) {
                return ref;
            }
            if (!parts.get(0).value().equalsIgnoreCase("EXCLUDED")) {
                return ref;
            }
            if (parts.size() != 2) {
                throw new UnsupportedFeatureException(
                        "EXCLUDED reference must be EXCLUDED.column for MySQL VALUES()",
                        ref.pos());
            }
            Identifier col = parts.get(1);
            ColumnRef bare = new ColumnRef(
                    new QualifiedName(List.of(col), col.pos()), col.pos());
            return new FunctionCall("VALUES", List.of(bare), false, Optional.empty(),
                    Optional.empty(), ref.pos());
        }

        private static boolean containsExcluded(Expression expr) {
            if (expr instanceof ColumnRef ref) {
                List<Identifier> parts = ref.name().parts();
                return !parts.isEmpty()
                        && parts.get(0).value().equalsIgnoreCase("EXCLUDED");
            }
            if (expr instanceof BinaryOp op) {
                return containsExcluded(op.left()) || containsExcluded(op.right());
            }
            if (expr instanceof UnaryOp op) {
                return containsExcluded(op.operand());
            }
            if (expr instanceof FunctionCall call) {
                for (Expression arg : call.args()) {
                    if (containsExcluded(arg)) {
                        return true;
                    }
                }
                return call.filter().map(RewriteUpsertRule.Rewriter::containsExcluded)
                        .orElse(false);
            }
            if (expr instanceof CastExpression cast) {
                return containsExcluded(cast.operand());
            }
            return false;
        }

        private Optional<Upsert> reshapeConflictNothing(Upsert upsert) {
            if (ctx.target() == Dialect.POSTGRESQL) {
                return Optional.of(upsert);
            }
            throw new UnsupportedFeatureException(
                    "ON CONFLICT DO NOTHING has no faithful MySQL form", upsert.pos());
        }

        private Optional<Upsert> reshapeDuplicate(Upsert upsert) {
            if (ctx.target() == Dialect.MYSQL) {
                return Optional.of(upsert);
            }
            // PostgreSQL needs a conflict target we do not know from ON DUPLICATE KEY alone.
            throw new UnsupportedFeatureException(
                    "ON DUPLICATE KEY UPDATE has no conflict target for PostgreSQL",
                    upsert.pos());
        }
    }
}
