package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.BinaryOp;
import rs.etf.sqltranslator.ast.BinaryOperator;
import rs.etf.sqltranslator.ast.CastExpression;
import rs.etf.sqltranslator.ast.DataType;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.GenericType;
import rs.etf.sqltranslator.ast.MaxLength;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Canonical → target function rendering: the function-mapping table as data plus the
 * few argument adapters that need code (ROADMAP Phase 4). Also lowers the canonical
 * CONCAT operator into each target's mechanism. Functions outside the table pass
 * through unchanged with a warning — the honest treatment of the vendor long tail.
 */
public final class RenderTargetFunctionsRule implements Rule {

    /** Canonical names with identical spelling and arity in all three targets. */
    private static final Set<String> UNIVERSAL = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX",
            "COALESCE", "UPPER", "LOWER", "TRIM", "LTRIM", "RTRIM",
            "REPLACE", "ABS", "ROUND", "FLOOR", "CEILING", "LEFT", "RIGHT", "SUBSTRING");

    /** Canonical names handled by rename tables or adapters below. */
    private static final Set<String> MAPPED = Set.of(
            "NOW", "CHAR_LENGTH", "YEAR", "MONTH", "DAY", "POSITION");

    /** PostgreSQL JSON builders / inspectors in the mapping table (Task 13). */
    private static final Set<String> JSON_FUNCTIONS = Set.of(
            "JSON_BUILD_OBJECT", "JSONB_BUILD_OBJECT",
            "JSON_BUILD_ARRAY", "JSONB_BUILD_ARRAY",
            "JSON_TYPEOF", "JSONB_TYPEOF",
            "JSONB_SET",
            "JSON_OBJECT_AGG", "JSONB_OBJECT_AGG", "JSONB_PRETTY");

    private static final Map<String, String> MYSQL_JSON_RENAMES = Map.ofEntries(
            Map.entry("JSON_BUILD_OBJECT", "JSON_OBJECT"),
            Map.entry("JSONB_BUILD_OBJECT", "JSON_OBJECT"),
            Map.entry("JSON_BUILD_ARRAY", "JSON_ARRAY"),
            Map.entry("JSONB_BUILD_ARRAY", "JSON_ARRAY"),
            Map.entry("JSON_TYPEOF", "JSON_TYPE"),
            Map.entry("JSONB_TYPEOF", "JSON_TYPE"),
            Map.entry("JSONB_SET", "JSON_SET"),
            Map.entry("JSON_OBJECT_AGG", "JSON_OBJECTAGG"),
            Map.entry("JSONB_OBJECT_AGG", "JSON_OBJECTAGG"));

    private static final Map<String, String> TSQL_JSON_RENAMES = Map.of(
            "JSONB_SET", "JSON_MODIFY");

    /** No faithful T-SQL equivalent — refused by name. */
    private static final Set<String> JSON_REFUSE_TSQL = Set.of(
            "JSON_BUILD_OBJECT", "JSONB_BUILD_OBJECT",
            "JSON_BUILD_ARRAY", "JSONB_BUILD_ARRAY",
            "JSON_TYPEOF", "JSONB_TYPEOF",
            "JSON_OBJECT_AGG", "JSONB_OBJECT_AGG", "JSONB_PRETTY");

    /** No faithful MySQL/T-SQL equivalent — refused by name. */
    private static final Set<String> JSON_REFUSE_NON_PG = Set.of(
            "JSONB_PRETTY");

    private static final Map<String, String> TSQL_RENAMES = Map.of(
            "NOW", "GETDATE",
            "CHAR_LENGTH", "LEN",
            "POSITION", "CHARINDEX");

    private static final Map<String, String> MYSQL_RENAMES = Map.of(
            "POSITION", "LOCATE");

    @Override
    public String name() {
        return "render-target-functions";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Renderer(ctx).transform(script);
    }

    private static final class Renderer extends ScopedTransformer {

        private Renderer(TranslationContext ctx) {
            super(ctx);
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            FunctionCall call = (FunctionCall) super.visitFunctionCall(node);
            if (call.star() || call.quantifier().isPresent()) {
                return call;
            }
            String name = call.name();
            if (name.equals("TRIM") && call.args().size() >= 2
                    && call.args().get(0) instanceof StringLiteral spec
                    && isTrimSpec(spec.value())) {
                return renderSpecifiedTrim(call, spec.value().toUpperCase(Locale.ROOT));
            }
            if (JSON_FUNCTIONS.contains(name)) {
                return renderJsonFunction(call);
            }
            if (name.equals("REGEXP_REPLACE")) {
                if (ctx.target() == Dialect.TSQL) {
                    throw new UnsupportedFeatureException(
                            "REGEXP_REPLACE is not supported by the target", call.pos());
                }
                return call;
            }
            if (name.equals("NEXTVAL") && ctx.target() == Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "NEXTVAL is not supported by the target", call.pos());
            }
            if (!UNIVERSAL.contains(name) && !MAPPED.contains(name)) {
                ctx.report().warn("FUNCTION_PASSTHROUGH",
                        "function " + name + " is outside the mapping table; "
                                + "passed through unchanged", call.pos());
                return call;
            }
            if (ctx.target() == Dialect.TSQL) {
                if (name.equals("SUBSTRING") && call.args().size() == 2) {
                    return substringAdapter(call);
                }
                String renamed = TSQL_RENAMES.getOrDefault(name, name);
                if (!renamed.equals(name)) {
                    return new FunctionCall(renamed, call.args(), false,
                            call.quantifier(), call.orderBy(), call.filter(),
                            call.window(), call.pos());
                }
                return call;
            }
            if (ctx.target() == Dialect.MYSQL) {
                String renamed = MYSQL_RENAMES.getOrDefault(name, name);
                if (!renamed.equals(name)) {
                    return new FunctionCall(renamed, call.args(), false,
                            call.quantifier(), call.orderBy(), call.filter(),
                            call.window(), call.pos());
                }
                return call;
            }
            if (ctx.target() == Dialect.POSTGRESQL
                    && (name.equals("YEAR") || name.equals("MONTH") || name.equals("DAY"))) {
                List<Expression> args = List.of(
                        new StringLiteral(name.toLowerCase(Locale.ROOT), false, call.pos()),
                        call.args().get(0));
                return new FunctionCall("DATE_PART", args, false,
                        call.quantifier(), call.orderBy(), call.filter(),
                        call.window(), call.pos());
            }
            return call;             // PostgreSQL: POSITION kept for IN-form printer
        }

        private Expression renderJsonFunction(FunctionCall call) {
            String name = call.name();
            if (ctx.target() == Dialect.POSTGRESQL) {
                return call;
            }
            if (JSON_REFUSE_NON_PG.contains(name)) {
                throw new UnsupportedFeatureException(
                        name + " is not supported by the target", call.pos());
            }
            if (ctx.target() == Dialect.TSQL) {
                if (JSON_REFUSE_TSQL.contains(name)) {
                    throw new UnsupportedFeatureException(
                            name + " is not supported by the target", call.pos());
                }
                String renamed = TSQL_JSON_RENAMES.get(name);
                if (renamed != null) {
                    return renamed(call, renamed);
                }
            }
            if (ctx.target() == Dialect.MYSQL) {
                String renamed = MYSQL_JSON_RENAMES.get(name);
                if (renamed != null) {
                    return renamed(call, renamed);
                }
            }
            throw new UnsupportedFeatureException(
                    name + " is not supported by the target", call.pos());
        }

        private static FunctionCall renamed(FunctionCall call, String targetName) {
            return new FunctionCall(targetName, call.args(), false,
                    call.quantifier(), call.orderBy(), call.filter(),
                    call.window(), call.pos());
        }

        private Expression renderSpecifiedTrim(FunctionCall call, String spec) {
            List<Expression> args = call.args();
            Expression source;
            Optional<Expression> chars;
            if (args.size() == 2) {
                source = args.get(1);
                chars = Optional.empty();
            } else if (args.size() == 3) {
                chars = Optional.of(args.get(1));
                source = args.get(2);
            } else {
                throw new UnsupportedFeatureException(
                        "TRIM with unexpected arity " + args.size(), call.pos());
            }
            if (ctx.target() == Dialect.TSQL) {
                return renderTsqlSpecifiedTrim(call, spec, source, chars);
            }
            // MySQL / PostgreSQL: keep TRIM(spec[, chars], source) for special printer form.
            return call;
        }

        private Expression renderTsqlSpecifiedTrim(FunctionCall call, String spec,
                                                   Expression source, Optional<Expression> chars) {
            if (chars.isPresent() && !isWhitespaceTrimChars(chars.get())) {
                throw new UnsupportedFeatureException(
                        "TRIM with non-whitespace character set on T-SQL", call.pos());
            }
            String targetName = switch (spec) {
                case "LEADING" -> "LTRIM";
                case "TRAILING" -> "RTRIM";
                case "BOTH" -> "TRIM";
                default -> throw new UnsupportedFeatureException(
                        "TRIM specification " + spec, call.pos());
            };
            return new FunctionCall(targetName, List.of(source), false,
                    call.quantifier(), call.orderBy(), call.filter(),
                    call.window(), call.pos());
        }

        private static boolean isTrimSpec(String value) {
            String upper = value.toUpperCase(Locale.ROOT);
            return upper.equals("LEADING") || upper.equals("TRAILING") || upper.equals("BOTH");
        }

        private static boolean isWhitespaceTrimChars(Expression chars) {
            if (!(chars instanceof StringLiteral lit)) {
                return false;
            }
            String value = lit.value();
            if (value.isEmpty()) {
                return true;
            }
            for (int i = 0; i < value.length(); i++) {
                if (!Character.isWhitespace(value.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        /** T-SQL SUBSTRING requires 3 args: append LEN(<string>) as the length. */
        private static FunctionCall substringAdapter(FunctionCall call) {
            List<Expression> args = new ArrayList<>(call.args());
            args.add(new FunctionCall("LEN", List.of(call.args().get(0)), false,
                    Optional.empty(), Optional.empty(), call.pos()));
            return new FunctionCall("SUBSTRING", args, false, call.quantifier(),
                    call.orderBy(), call.filter(), call.window(), call.pos());
        }

        @Override
        public Object visitBinaryOp(BinaryOp node) {
            BinaryOp op = (BinaryOp) super.visitBinaryOp(node);
            if (op.op() != BinaryOperator.CONCAT) {
                return op;
            }
            if (ctx.target() == Dialect.MYSQL) {
                // Bottom-up: an inner chain is already a CONCAT(...) call — merge it.
                List<Expression> args = new ArrayList<>();
                flatten(op.left(), args);
                flatten(op.right(), args);
                return new FunctionCall("CONCAT", args, false, Optional.empty(),
                        Optional.empty(), op.pos());
            }
            if (ctx.target() == Dialect.TSQL) {
                return new BinaryOp(BinaryOperator.CONCAT,
                        stringOperand(op.left()), stringOperand(op.right()), op.pos());
            }
            return op;               // PostgreSQL prints ||
        }

        private static void flatten(Expression expr, List<Expression> into) {
            if (expr instanceof FunctionCall call && call.name().equals("CONCAT")
                    && !call.star() && call.quantifier().isEmpty()) {
                into.addAll(call.args());
            } else {
                into.add(expr);
            }
        }

        /** T-SQL '+' concatenates only strings: cast known non-strings, warn on unknown. */
        private Expression stringOperand(Expression operand) {
            SourcePosition pos = exprPos(operand);
            Optional<TypeFamily> family = familyOf(operand);
            if (family.isEmpty()) {
                ctx.report().warn("CONCAT_OPERAND_UNRESOLVED",
                        "concat operand type unknown; emitted without CAST — "
                                + "T-SQL '+' may fail or add", pos);
                return operand;
            }
            if (family.get() == TypeFamily.STRING) {
                return operand;
            }
            return new CastExpression(operand,
                    new DataType(GenericType.NVARCHAR, Optional.of(new MaxLength()),
                            Optional.empty()), pos);
        }

        private static SourcePosition exprPos(Expression expr) {
            if (expr instanceof rs.etf.sqltranslator.ast.ColumnRef ref) {
                return ref.pos();
            }
            if (expr instanceof rs.etf.sqltranslator.ast.NumericLiteral n) {
                return n.pos();
            }
            if (expr instanceof StringLiteral s) {
                return s.pos();
            }
            if (expr instanceof BinaryOp op) {
                return op.pos();
            }
            if (expr instanceof FunctionCall call) {
                return call.pos();
            }
            if (expr instanceof CastExpression cast) {
                return cast.pos();
            }
            throw new IllegalArgumentException("no position on expression: " + expr.getClass());
        }
    }
}
