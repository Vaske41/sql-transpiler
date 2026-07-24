package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SetQuantifier;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;

/**
 * Reshape ordered string aggregates across dialects:
 * {@code STRING_AGG(x, sep [ORDER BY …])} ↔ {@code GROUP_CONCAT(x [ORDER BY …] SEPARATOR sep)},
 * refuse {@code DISTINCT} string aggregates toward T-SQL (no {@code STRING_AGG(DISTINCT)}),
 * and refuse in-arg {@code ORDER BY} on aggregates T-SQL cannot express as
 * {@code WITHIN GROUP}.
 */
public final class ReshapeOrderedAggregatesRule implements Rule {

    @Override
    public String name() {
        return "reshape-ordered-aggregates";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        return new Reshaper(ctx).transform(script);
    }

    private static final class Reshaper extends AstTransformer {

        private final TranslationContext ctx;

        private Reshaper(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitFunctionCall(FunctionCall node) {
            FunctionCall call = (FunctionCall) super.visitFunctionCall(node);
            call = reshapeName(call);
            refuseDistinctStringAggToTsql(call);
            refuseUnsupportedOrderedAggregate(call);
            return call;
        }

        private FunctionCall reshapeName(FunctionCall call) {
            if (ctx.target() == Dialect.MYSQL && call.name().equals("STRING_AGG")) {
                return new FunctionCall("GROUP_CONCAT", withSeparator(call), false,
                        call.quantifier(), call.orderBy(), call.filter(),
                        call.window(), call.pos());
            }
            if ((ctx.target() == Dialect.POSTGRESQL || ctx.target() == Dialect.TSQL)
                    && call.name().equals("GROUP_CONCAT")) {
                return new FunctionCall("STRING_AGG", withSeparator(call), false,
                        call.quantifier(), call.orderBy(), call.filter(),
                        call.window(), call.pos());
            }
            return call;
        }

        /** Ensure {@code (expr, sep)} — default separator is comma. */
        private static List<Expression> withSeparator(FunctionCall call) {
            if (call.args().isEmpty()) {
                return call.args();
            }
            if (call.args().size() == 1) {
                return List.of(call.args().get(0),
                        new StringLiteral(",", false, call.pos()));
            }
            return call.args();
        }

        /**
         * SQL Server {@code STRING_AGG} rejects {@code DISTINCT}; refuse rather than
         * emit invalid T-SQL from {@code GROUP_CONCAT(DISTINCT …)} / {@code STRING_AGG(DISTINCT …)}.
         */
        private void refuseDistinctStringAggToTsql(FunctionCall call) {
            if (ctx.target() != Dialect.TSQL) {
                return;
            }
            if (!call.name().equals("STRING_AGG") && !call.name().equals("GROUP_CONCAT")) {
                return;
            }
            if (call.quantifier().orElse(null) != SetQuantifier.DISTINCT) {
                return;
            }
            throw new UnsupportedFeatureException("STRING_AGG DISTINCT", call.pos());
        }

        private void refuseUnsupportedOrderedAggregate(FunctionCall call) {
            if (call.orderBy().isEmpty()) {
                return;
            }
            if (ctx.target() == Dialect.TSQL && !call.name().equals("STRING_AGG")) {
                throw new UnsupportedFeatureException(
                        "ordered aggregate " + call.name()
                                + " (no in-arg ORDER BY in T-SQL)",
                        call.pos());
            }
        }
    }
}
