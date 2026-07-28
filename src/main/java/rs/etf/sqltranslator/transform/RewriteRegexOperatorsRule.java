package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.UnaryOp;
import rs.etf.sqltranslator.ast.UnaryOperator;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL regex operators ({@code ~}, {@code ~*}, {@code !~}, {@code !~*}) toward MySQL
 * {@code REGEXP} / {@code REGEXP_LIKE}; refused toward T-SQL (no faithful regex operator).
 */
public final class RewriteRegexOperatorsRule implements Rule {

    @Override
    public String name() {
        return "rewrite-regex-operators";
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
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (!isRegexOp(op.op())) {
                return op;
            }
            if (ctx.target() == Dialect.POSTGRESQL) {
                return op;
            }
            if (ctx.target() == Dialect.MYSQL) {
                return towardMysql(op);
            }
            throw new UnsupportedFeatureException(
                    "PostgreSQL regex operator is not supported by the target", op.pos());
        }

        private Expression towardMysql(BinaryOp op) {
            return switch (op.op()) {
                case REGEX_MATCH -> regexpLike(op, "c", op.pos());
                case REGEX_MATCH_I -> regexpLike(op, "i", op.pos());
                case REGEX_NOT_MATCH -> new UnaryOp(UnaryOperator.NOT,
                        regexpLike(op, "c", op.pos()), op.pos());
                case REGEX_NOT_MATCH_I -> new UnaryOp(UnaryOperator.NOT,
                        regexpLike(op, "i", op.pos()), op.pos());
                default -> op;
            };
        }

        private static FunctionCall regexpLike(BinaryOp op, String flags, SourcePosition pos) {
            return new FunctionCall("REGEXP_LIKE",
                    List.of(op.left(), op.right(), new StringLiteral(flags, false, pos)),
                    false, Optional.empty(), Optional.empty(), pos);
        }

        private static boolean isRegexOp(BinaryOperator op) {
            return op == BinaryOperator.REGEX_MATCH
                    || op == BinaryOperator.REGEX_MATCH_I
                    || op == BinaryOperator.REGEX_NOT_MATCH
                    || op == BinaryOperator.REGEX_NOT_MATCH_I;
        }
    }
}
