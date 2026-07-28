package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.IntervalLiteral;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * INTERVAL handling for targets that lack native interval literals.
 * <ul>
 *   <li>Toward T-SQL: {@code date ± INTERVAL n unit} and {@code DATE_ADD}/{@code DATE_SUB}
 *       → {@code DATEADD(unit, ±n, date)}. Residuals (standalone / compound / non-additive)
 *       are refused here — not in {@link ValidateTargetCapabilitiesRule} (§2.4).</li>
 *   <li>Toward PostgreSQL: {@code DATE_ADD}/{@code DATE_SUB} → {@code date ± interval}.</li>
 *   <li>Toward MySQL: compound intervals (no extractable unit) are refused; simple
 *       forms render natively via the MySQL printer.</li>
 * </ul>
 */
public final class RewriteIntervalArithmeticRule implements Rule {

    /** Portable units accepted by T-SQL {@code DATEADD}; unknowns are refused. */
    private static final Set<String> DATEADD_UNITS = Set.of(
            "year", "quarter", "month", "day", "week",
            "hour", "minute", "second", "millisecond");

    @Override
    public String name() {
        return "rewrite-interval-arithmetic";
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
        public Object visitBinaryOp(BinaryOp node) {
            if (ctx.target() == Dialect.TSQL) {
                Expression rewritten = tryDateAdd(node);
                if (rewritten != null) {
                    return rewritten;
                }
            }
            return super.visitBinaryOp(node);
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            Expression rewritten = tryDateAddFunction(node);
            if (rewritten != null) {
                return rewritten;
            }
            return super.visitFunctionCall(node);
        }

        @Override
        public Object visitIntervalLiteral(IntervalLiteral node) {
            if (ctx.target() == Dialect.POSTGRESQL) {
                return super.visitIntervalLiteral(node);
            }
            IntervalLiteral rebuilt = (IntervalLiteral) super.visitIntervalLiteral(node);
            if (rebuilt.unit().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "compound INTERVAL literal", rebuilt.pos());
            }
            if (ctx.target() == Dialect.TSQL) {
                // Residual: not consumed by date ± INTERVAL → DATEADD above.
                throw new UnsupportedFeatureException(
                        "INTERVAL literal (not in additive date context)", rebuilt.pos());
            }
            return rebuilt; // MySQL: native render
        }

        /**
         * {@code DATE_ADD}/{@code DATE_SUB}/{@code ADDDATE}/{@code SUBDATE}(date, INTERVAL).
         * Intercepted before children so T-SQL does not refuse the nested interval.
         */
        private Expression tryDateAddFunction(FunctionCall node) {
            if (node.star() || node.args().size() != 2) {
                return null;
            }
            if (!(node.args().get(1) instanceof IntervalLiteral interval)
                    || interval.unit().isEmpty()) {
                return null;
            }
            String name = node.name().toUpperCase(Locale.ROOT);
            int sign = switch (name) {
                case "DATE_ADD", "ADDDATE" -> 1;
                case "DATE_SUB", "SUBDATE" -> -1;
                default -> 0;
            };
            if (sign == 0) {
                return null;
            }
            Expression date = rebuild(node.args().get(0));
            IntervalLiteral rebuiltInterval = new IntervalLiteral(
                    rebuild(interval.value()), interval.unit(), interval.pos());
            if (ctx.target() == Dialect.TSQL) {
                return dateAdd(rebuiltInterval, sign, date, node.pos());
            }
            if (ctx.target() == Dialect.POSTGRESQL) {
                BinaryOperator op = sign < 0 ? BinaryOperator.SUB : BinaryOperator.ADD;
                return new BinaryOp(op, date, rebuiltInterval, node.pos());
            }
            return null; // MySQL keeps DATE_ADD / DATE_SUB
        }

        /**
         * {@code date ± INTERVAL n unit} or {@code INTERVAL n unit + date}.
         * Rebuilds the date side only so the interval is consumed without hitting
         * {@link #visitIntervalLiteral}'s T-SQL refusal.
         */
        private Expression tryDateAdd(BinaryOp node) {
            BinaryOperator op = node.op();
            if (op != BinaryOperator.ADD && op != BinaryOperator.SUB) {
                return null;
            }
            if (node.right() instanceof IntervalLiteral interval && interval.unit().isPresent()) {
                Expression date = rebuild(node.left());
                IntervalLiteral rebuilt = new IntervalLiteral(
                        rebuild(interval.value()), interval.unit(), interval.pos());
                int sign = op == BinaryOperator.SUB ? -1 : 1;
                return dateAdd(rebuilt, sign, date, node.pos());
            }
            if (op == BinaryOperator.ADD
                    && node.left() instanceof IntervalLiteral interval
                    && interval.unit().isPresent()) {
                Expression date = rebuild(node.right());
                IntervalLiteral rebuilt = new IntervalLiteral(
                        rebuild(interval.value()), interval.unit(), interval.pos());
                return dateAdd(rebuilt, 1, date, node.pos());
            }
            return null;
        }

        private static FunctionCall dateAdd(IntervalLiteral interval, int sign,
                                            Expression date, SourcePosition pos) {
            String unit = interval.unit().orElseThrow().toLowerCase(Locale.ROOT);
            if (!DATEADD_UNITS.contains(unit)) {
                throw new UnsupportedFeatureException(
                        "INTERVAL unit '" + unit + "' toward DATEADD", interval.pos());
            }
            Expression amount = interval.value();
            if (sign < 0) {
                if (amount instanceof NumericLiteral num) {
                    String magnitude = num.text().trim();
                    String signed = magnitude.startsWith("-")
                            ? magnitude.substring(1)
                            : "-" + magnitude;
                    amount = new NumericLiteral(signed, signed.contains("."), pos);
                } else {
                    amount = new BinaryOp(BinaryOperator.MUL, amount,
                            new NumericLiteral("-1", false, pos), pos);
                }
            }
            Identifier unitId = new Identifier(unit, false, pos);
            ColumnRef unitRef = new ColumnRef(new QualifiedName(List.of(unitId), pos), pos);
            return new FunctionCall("DATEADD", List.of(unitRef, amount, date),
                    false, Optional.empty(), Optional.empty(), pos);
        }
    }
}
