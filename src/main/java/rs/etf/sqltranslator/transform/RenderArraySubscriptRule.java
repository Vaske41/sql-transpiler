package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.ArraySubscript;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * PostgreSQL 1-based array subscripts → target JSON path access.
 * <ul>
 *   <li>PostgreSQL — native {@code base[index]}.</li>
 *   <li>MySQL — {@code JSON_EXTRACT(base, '$[n-1]')} or
 *       {@code JSON_EXTRACT(base, CONCAT('$[', index - 1, ']'))}.</li>
 *   <li>T-SQL — {@code JSON_VALUE(base, '$[n-1]')} or the same {@code CONCAT} path form.</li>
 * </ul>
 * PG arrays are 1-based; JSON paths are 0-based — the index shift is load-bearing.
 */
public final class RenderArraySubscriptRule implements Rule {

    @Override
    public String name() {
        return "render-array-subscript";
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
        public Object visitArraySubscript(ArraySubscript node) {
            ArraySubscript sub = (ArraySubscript) super.visitArraySubscript(node);
            if (ctx.target() == Dialect.POSTGRESQL) {
                return sub;
            }
            if (ctx.target() == Dialect.MYSQL) {
                return jsonExtract(sub);
            }
            return jsonValue(sub);
        }

        private FunctionCall jsonExtract(ArraySubscript sub) {
            return new FunctionCall(
                    "JSON_EXTRACT",
                    List.of(sub.base(), jsonPath(sub.index(), sub.pos())),
                    false, Optional.empty(), Optional.empty(), sub.pos());
        }

        private FunctionCall jsonValue(ArraySubscript sub) {
            return new FunctionCall(
                    "JSON_VALUE",
                    List.of(sub.base(), jsonPath(sub.index(), sub.pos())),
                    false, Optional.empty(), Optional.empty(), sub.pos());
        }

        private Expression jsonPath(Expression index, SourcePosition pos) {
            return foldZeroBasedPath(index, pos).<Expression>map(
                    path -> new StringLiteral(path, false, pos))
                    .orElseGet(() -> concatJsonPath(index, pos));
        }

        /** Constant-fold {@code '$[pgIndex - 1]'} when the subscript is a numeric literal. */
        private static Optional<String> foldZeroBasedPath(Expression index, SourcePosition pos) {
            OptionalInt zeroBased = zeroBasedIndex(index);
            return zeroBased.isPresent()
                    ? Optional.of("$[" + zeroBased.getAsInt() + "]")
                    : Optional.empty();
        }

        private static OptionalInt zeroBasedIndex(Expression index) {
            if (!(index instanceof NumericLiteral num)) {
                return OptionalInt.empty();
            }
            if (num.decimal() && num.text().contains(".")) {
                return OptionalInt.empty();
            }
            try {
                int pgIndex = Integer.parseInt(num.text());
                return OptionalInt.of(pgIndex - 1);
            } catch (NumberFormatException e) {
                return OptionalInt.empty();
            }
        }

        /** {@code CONCAT('$[', index - 1, ']')} for non-literal subscripts. */
        private static FunctionCall concatJsonPath(Expression index, SourcePosition pos) {
            Expression zeroBased = new BinaryOp(
                    BinaryOperator.SUB,
                    index,
                    new NumericLiteral("1", false, pos),
                    pos);
            return new FunctionCall(
                    "CONCAT",
                    List.of(
                            new StringLiteral("$[", false, pos),
                            zeroBased,
                            new StringLiteral("]", false, pos)),
                    false, Optional.empty(), Optional.empty(), pos);
        }
    }
}
