package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.JsonTableRelation;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.TableFunction;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL set-returning functions in FROM position → MySQL {@code JSON_TABLE}.
 * Carries the SRF refusal as its own fall-through: the first-pass validator must not
 * refuse table functions toward MySQL, or this rewrite would never run (RuleEngine
 * order is load-bearing — see plan §5).
 */
public final class RenderSrfForMysqlRule implements Rule {

    private static final Set<String> JSON_ARRAY_SRF = Set.of(
            "JSON_ARRAY_ELEMENTS", "JSONB_ARRAY_ELEMENTS",
            "JSON_ARRAY_ELEMENTS_TEXT", "JSONB_ARRAY_ELEMENTS_TEXT");

    private static final Set<String> JSON_TO_RECORD = Set.of(
            "JSON_TO_RECORD", "JSONB_TO_RECORD",
            "JSON_TO_RECORDSET", "JSONB_TO_RECORDSET");

    @Override
    public String name() {
        return "render-srf-mysql";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.MYSQL) {
            return script;
        }
        return new Renderer().transform(script);
    }

    private static final class Renderer extends AstTransformer {

        @Override
        public Object visitTableFunction(TableFunction node) {
            String fn = node.name().last().value().toUpperCase(Locale.ROOT);

            if (JSON_ARRAY_SRF.contains(fn) && node.args().size() == 1) {
                boolean asText = fn.endsWith("_TEXT");
                return JsonTableRelation.arrayElements(
                        rebuild(node.args().get(0)), node.alias(), node.pos(), asText);
            }

            if (JSON_TO_RECORD.contains(fn) && node.args().size() == 1
                    && !node.columnTypes().isEmpty()) {
                return JsonTableRelation.objectFields(
                        rebuild(node.args().get(0)),
                        rebuildList(node.columnTypes()),
                        node.alias(),
                        node.pos());
            }

            if ("UNNEST".equals(fn) && node.args().size() == 1) {
                Expression arg = rebuild(node.args().get(0));
                if (arg instanceof FunctionCall call
                        && "STRING_TO_ARRAY".equals(call.name())
                        && call.args().size() == 2) {
                    Expression source = stringSplitAsJsonArray(
                            call.args().get(0), call.args().get(1), node.pos());
                    return JsonTableRelation.arrayElements(
                            source, node.alias(), node.pos(), true);
                }
                throw new UnsupportedFeatureException(
                        "table function UNNEST (no MySQL JSON_TABLE form for array column)",
                        node.pos());
            }

            throw new UnsupportedFeatureException(
                    "table function " + fn + " (no MySQL JSON_TABLE form)", node.pos());
        }

        /**
         * {@code unnest(string_to_array(s, d))} → JSON array literal for {@code JSON_TABLE}.
         * Escapes {@code \} then {@code "} in {@code s}, then replaces delimiter with
         * {@code ","}, wrapped as {@code ["…"]}. MySQL 8.0 has no {@code STRING_SPLIT}.
         * Literal delimiters containing {@code \} or {@code "} are refused — escape-then-split
         * cannot faithfully recover element boundaries for those delimiters.
         */
        private static Expression stringSplitAsJsonArray(Expression str, Expression delim,
                                                         SourcePosition pos) {
            if (delim instanceof StringLiteral lit) {
                String d = lit.value();
                if (d.contains("\\") || d.contains("\"")) {
                    throw new UnsupportedFeatureException(
                            "unnest(string_to_array) delimiter containing \\ or \""
                                    + " (no faithful MySQL JSON encoding)",
                            pos);
                }
            }
            // Order is load-bearing: backslash first, then quote, then delimiter split.
            Expression escaped = replaceCall(str,
                    new StringLiteral("\\", false, pos),
                    new StringLiteral("\\\\", false, pos),
                    pos);
            escaped = replaceCall(escaped,
                    new StringLiteral("\"", false, pos),
                    new StringLiteral("\\\"", false, pos),
                    pos);
            Expression split = replaceCall(escaped, delim, new StringLiteral("\",\"", false, pos), pos);
            return new FunctionCall(
                    "CONCAT",
                    List.of(
                            new StringLiteral("[\"", false, pos),
                            split,
                            new StringLiteral("\"]", false, pos)),
                    false, Optional.empty(), Optional.empty(), pos);
        }

        private static FunctionCall replaceCall(Expression input, Expression from, Expression to,
                                                SourcePosition pos) {
            return new FunctionCall(
                    "REPLACE",
                    List.of(input, from, to),
                    false, Optional.empty(), Optional.empty(), pos);
        }
    }
}
