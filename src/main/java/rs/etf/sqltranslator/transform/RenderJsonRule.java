package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Render JSON access operators for the target dialect.
 * <ul>
 *   <li>PostgreSQL — native ({@code ->}, {@code ->>}, {@code #>}, {@code #>>}, {@code @>}).</li>
 *   <li>MySQL — {@code ->}/{@code ->>} with {@code $.…} paths; {@code #>}{@code #>>}
 *       collapsed to a single arrow with a combined path; containment refused.</li>
 *   <li>T-SQL — {@code ->}→{@code JSON_QUERY}, {@code ->>}→{@code JSON_VALUE} (and path
 *       forms) with {@code $.k} paths from literal keys; non-literal keys and
 *       containment refused.</li>
 * </ul>
 * Refusals live here (fall-through), not in {@link ValidateTargetCapabilitiesRule}.
 */
public final class RenderJsonRule implements Rule {

    @Override
    public String name() {
        return "render-json";
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
            if (!isJsonOp(op.op())) {
                return op;
            }
            if (ctx.target() == Dialect.POSTGRESQL) {
                return op;
            }
            if (ctx.target() == Dialect.MYSQL) {
                return towardMysql(op);
            }
            return towardTsql(op);
        }

        private Expression towardMysql(BinaryOp op) {
            return switch (op.op()) {
                case JSON_GET, JSON_GET_TEXT -> withMysqlKeyPath(op);
                case JSON_PATH -> collapsePath(op, false);
                case JSON_PATH_TEXT -> collapsePath(op, true);
                case JSON_CONTAINS -> refuseContains(op);
                default -> op;
            };
        }

        private Expression towardTsql(BinaryOp op) {
            return switch (op.op()) {
                case JSON_GET -> jsonFunction("JSON_QUERY", op.left(), singleKeyPath(op), op.pos());
                case JSON_GET_TEXT -> jsonFunction("JSON_VALUE", op.left(), singleKeyPath(op), op.pos());
                case JSON_PATH -> jsonFunction("JSON_QUERY", op.left(), multiKeyPath(op), op.pos());
                case JSON_PATH_TEXT -> jsonFunction("JSON_VALUE", op.left(), multiKeyPath(op), op.pos());
                case JSON_CONTAINS -> refuseContains(op);
                default -> op;
            };
        }

        /** Rewrite a single-key arrow so MySQL gets a {@code $.…} path literal. */
        private Expression withMysqlKeyPath(BinaryOp op) {
            if (!(op.right() instanceof StringLiteral lit)) {
                throw new UnsupportedFeatureException(
                        "JSON access with non-literal key toward MySQL", op.pos());
            }
            String path = toDollarPath(lit.value());
            if (path.equals(lit.value())) {
                return op;
            }
            return new BinaryOp(op.op(), op.left(),
                    new StringLiteral(path, lit.national(), op.pos()), op.pos());
        }

        /**
         * Collapse {@code #>} / {@code #>>} to a single MySQL {@code ->} / {@code ->>}
         * with a combined {@code $.a.b} path (not a multi-arrow chain).
         */
        private Expression collapsePath(BinaryOp op, boolean asText) {
            List<String> keys = pathKeys(op);
            BinaryOperator arrow = asText
                    ? BinaryOperator.JSON_GET_TEXT
                    : BinaryOperator.JSON_GET;
            return new BinaryOp(arrow, op.left(),
                    new StringLiteral(multiDollarPath(keys), false, op.pos()), op.pos());
        }

        private String singleKeyPath(BinaryOp op) {
            if (!(op.right() instanceof StringLiteral lit)) {
                throw new UnsupportedFeatureException(
                        "JSON access with non-literal key toward T-SQL", op.pos());
            }
            return toDollarPath(lit.value());
        }

        private String multiKeyPath(BinaryOp op) {
            return multiDollarPath(pathKeys(op));
        }

        /**
         * Build a MySQL/T-SQL JSON path from a bare key, preserving an existing
         * {@code $…} prefix (avoids {@code $.$.id} for MySQL-sourced paths).
         */
        private static String toDollarPath(String key) {
            if (key.startsWith("$")) {
                return key;
            }
            if (isNumericPathLeg(key)) {
                return "$[" + key + "]";
            }
            return "$." + key;
        }

        private static String multiDollarPath(List<String> keys) {
            StringBuilder path = new StringBuilder("$");
            for (String key : keys) {
                if (isNumericPathLeg(key)) {
                    path.append('[').append(key).append(']');
                } else {
                    path.append('.').append(key);
                }
            }
            return path.toString();
        }

        private static boolean isNumericPathLeg(String key) {
            if (key.isEmpty()) {
                return false;
            }
            for (int i = 0; i < key.length(); i++) {
                if (!Character.isDigit(key.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private List<String> pathKeys(BinaryOp op) {
            if (!(op.right() instanceof StringLiteral lit)) {
                throw new UnsupportedFeatureException(
                        "JSON path with non-literal array toward "
                                + ctx.target().name().toLowerCase(Locale.ROOT),
                        op.pos());
            }
            String raw = lit.value().trim();
            if (raw.length() < 2 || raw.charAt(0) != '{' || raw.charAt(raw.length() - 1) != '}') {
                throw new UnsupportedFeatureException(
                        "JSON path literal must be '{k1,k2,…}'", op.pos());
            }
            String body = raw.substring(1, raw.length() - 1).trim();
            if (body.isEmpty()) {
                throw new UnsupportedFeatureException("empty JSON path", op.pos());
            }
            List<String> keys = new ArrayList<>();
            for (String part : body.split(",")) {
                String key = part.trim();
                if (key.length() >= 2 && key.charAt(0) == '"' && key.charAt(key.length() - 1) == '"') {
                    key = key.substring(1, key.length() - 1);
                }
                if (key.isEmpty()) {
                    throw new UnsupportedFeatureException("empty JSON path key", op.pos());
                }
                keys.add(key);
            }
            return keys;
        }

        private static Expression jsonFunction(String name, Expression doc, String path,
                                              SourcePosition pos) {
            return new FunctionCall(name,
                    List.of(doc, new StringLiteral(path, false, pos)),
                    false, Optional.empty(), Optional.empty(), pos);
        }

        private static Expression refuseContains(BinaryOp op) {
            throw new UnsupportedFeatureException(
                    "JSON containment (@>) is not supported by the target", op.pos());
        }

        private static boolean isJsonOp(BinaryOperator op) {
            return op == BinaryOperator.JSON_GET
                    || op == BinaryOperator.JSON_GET_TEXT
                    || op == BinaryOperator.JSON_PATH
                    || op == BinaryOperator.JSON_PATH_TEXT
                    || op == BinaryOperator.JSON_CONTAINS;
        }
    }
}
