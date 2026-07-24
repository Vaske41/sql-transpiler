package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.IsBoolPredicate;
import rs.etf.sqltranslator.ast.IsNullPredicate;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;

/**
 * Rewrite {@code IS [NOT] TRUE/FALSE/UNKNOWN} where the target cannot express it:
 * <ul>
 *   <li>T-SQL: no {@code IS TRUE/FALSE/UNKNOWN} — equality / null tests</li>
 *   <li>MySQL: {@code IS TRUE/FALSE} native; {@code IS UNKNOWN} → {@code IS NULL}</li>
 *   <li>PostgreSQL: leave native</li>
 * </ul>
 */
public final class RewriteIsBoolPredicateRule implements Rule {

    @Override
    public String name() {
        return "rewrite-is-bool-predicate";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() == Dialect.POSTGRESQL) {
            return script;
        }
        return new Rewriter(ctx.target()).transform(script);
    }

    private static final class Rewriter extends AstTransformer {

        private final Dialect target;

        private Rewriter(Dialect target) {
            this.target = target;
        }

        @Override
        public Object visitIsBoolPredicate(IsBoolPredicate node) {
            IsBoolPredicate rebuilt = (IsBoolPredicate) super.visitIsBoolPredicate(node);
            if (target == Dialect.MYSQL && rebuilt.test() != rs.etf.sqltranslator.ast.BoolTest.UNKNOWN) {
                return rebuilt; // MySQL prints IS [NOT] TRUE/FALSE natively
            }
            Expression value = rebuilt.value();
            return switch (rebuilt.test()) {
                case UNKNOWN -> new IsNullPredicate(value, rebuilt.negated(), rebuilt.pos());
                case TRUE -> rebuilt.negated()
                        ? orNullOrEquals(value, false, rebuilt.pos())
                        : eq(value, true, rebuilt.pos());
                case FALSE -> rebuilt.negated()
                        ? orNullOrEquals(value, true, rebuilt.pos())
                        : eq(value, false, rebuilt.pos());
            };
        }

        private static Expression eq(Expression value, boolean literal, SourcePosition pos) {
            return new BinaryOp(BinaryOperator.EQ, value,
                    new BooleanLiteral(literal, pos), pos);
        }

        private static Expression orNullOrEquals(Expression value, boolean literal,
                                                 SourcePosition pos) {
            return new BinaryOp(
                    BinaryOperator.OR,
                    new IsNullPredicate(value, false, pos),
                    eq(value, literal, pos),
                    pos);
        }
    }
}
