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

/**
 * INTERVAL handling for targets that lack native interval literals.
 * <ul>
 *   <li>Toward T-SQL: {@code date ± INTERVAL n unit} → {@code DATEADD(unit, ±n, date)}.
 *       Residuals (standalone / compound / non-additive) are refused here — not in
 *       {@link ValidateTargetCapabilitiesRule} (§2.4).</li>
 *   <li>Toward MySQL: compound intervals (no extractable unit) are refused; simple
 *       forms render natively via the MySQL printer.</li>
 *   <li>Toward PostgreSQL: no-op (native).</li>
 * </ul>
 */
public final class RewriteIntervalArithmeticRule implements Rule {

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
        public Object visitIntervalLiteral(IntervalLiteral node) {
            if (ctx.target() == Dialect.POSTGRESQL) {
                return node;
            }
            if (node.unit().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "compound INTERVAL literal", node.pos());
            }
            if (ctx.target() == Dialect.TSQL) {
                // Residual: not consumed by date ± INTERVAL → DATEADD above.
                throw new UnsupportedFeatureException(
                        "INTERVAL literal (not in additive date context)", node.pos());
            }
            return node; // MySQL: native render
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
                int sign = op == BinaryOperator.SUB ? -1 : 1;
                return dateAdd(interval, sign, date, node.pos());
            }
            if (op == BinaryOperator.ADD
                    && node.left() instanceof IntervalLiteral interval
                    && interval.unit().isPresent()) {
                Expression date = rebuild(node.right());
                return dateAdd(interval, 1, date, node.pos());
            }
            return null;
        }

        private static FunctionCall dateAdd(IntervalLiteral interval, int sign,
                                            Expression date, SourcePosition pos) {
            String unit = interval.unit().orElseThrow();
            String magnitude = interval.raw().trim();
            String signed = sign < 0
                    ? (magnitude.startsWith("-") ? magnitude.substring(1) : "-" + magnitude)
                    : magnitude;
            Identifier unitId = new Identifier(unit.toLowerCase(Locale.ROOT), false, pos);
            ColumnRef unitRef = new ColumnRef(new QualifiedName(List.of(unitId), pos), pos);
            NumericLiteral amount = new NumericLiteral(signed, signed.contains("."), pos);
            return new FunctionCall("DATEADD", List.of(unitRef, amount, date),
                    false, Optional.empty(), Optional.empty(), pos);
        }
    }
}
