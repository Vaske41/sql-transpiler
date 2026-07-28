package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SetQuantifier;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.Set;

/**
 * PostgreSQL {@code ARRAY_AGG} / {@code JSON_AGG} / {@code JSONB_AGG} → MySQL
 * {@code JSON_ARRAYAGG}. T-SQL still has no array/JSON-array aggregate — refused
 * in {@link ValidateTargetCapabilitiesRule}.
 *
 * <p>Representation note (Task 25): PG {@code array_agg} yields a native array
 * ({@code {1,2,3}}); {@code JSON_ARRAYAGG} yields JSON ({@code [1,2,3]}).
 */
public final class RenderArrayAggregatesRule implements Rule {

    static final Set<String> ARRAY_AGGREGATES =
            Set.of("ARRAY_AGG", "JSON_AGG", "JSONB_AGG");

    @Override
    public String name() {
        return "render-array-aggregates";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.MYSQL) {
            return script;
        }
        return new Rewriter().transform(script);
    }

    private static final class Rewriter extends AstTransformer {

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            FunctionCall call = (FunctionCall) super.visitFunctionCall(node);
            if (!ARRAY_AGGREGATES.contains(call.name())) {
                return call;
            }
            if (!call.orderBy().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "ordered " + call.name()
                                + " (MySQL JSON_ARRAYAGG has no in-arg ORDER BY)",
                        call.pos());
            }
            if (call.quantifier().orElse(null) == SetQuantifier.DISTINCT) {
                throw new UnsupportedFeatureException(
                        "distinct " + call.name()
                                + " (MySQL JSON_ARRAYAGG has no DISTINCT)",
                        call.pos());
            }
            return new FunctionCall(
                    "JSON_ARRAYAGG",
                    call.args(),
                    call.star(),
                    call.quantifier(),
                    call.orderBy(),
                    call.filter(),
                    call.window(),
                    call.pos());
        }
    }
}
