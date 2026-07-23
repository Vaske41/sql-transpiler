package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.CaseExpression;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.WhenClause;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rewrite {@code agg(…) FILTER (WHERE p)} toward MySQL/T-SQL as
 * {@code agg(CASE WHEN p THEN … END)}. PostgreSQL keeps native {@code FILTER}.
 * {@code COUNT(*) FILTER (WHERE p)} becomes {@code COUNT(CASE WHEN p THEN 1 END)}.
 */
public final class RewriteFilteredAggregatesRule implements Rule {

    @Override
    public String name() {
        return "rewrite-filtered-aggregates";
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
        public Object visitFunctionCall(FunctionCall node) {
            FunctionCall call = (FunctionCall) super.visitFunctionCall(node);
            if (call.filter().isEmpty() || ctx.target() == Dialect.POSTGRESQL) {
                return call;
            }
            Expression predicate = call.filter().get();
            SourcePosition pos = call.pos();
            if (call.star()) {
                CaseExpression wrapped = searchedCase(predicate,
                        new NumericLiteral("1", false, pos), pos);
                return new FunctionCall(call.name(), List.of(wrapped), false,
                        call.quantifier(), call.orderBy(), Optional.empty(),
                        call.window(), pos);
            }
            if (call.args().isEmpty()) {
                return new FunctionCall(call.name(), call.args(), false,
                        call.quantifier(), call.orderBy(), Optional.empty(),
                        call.window(), pos);
            }
            List<Expression> args = new ArrayList<>(call.args());
            args.set(0, searchedCase(predicate, args.get(0), pos));
            return new FunctionCall(call.name(), List.copyOf(args), false,
                    call.quantifier(), call.orderBy(), Optional.empty(),
                    call.window(), pos);
        }

        private static CaseExpression searchedCase(Expression predicate, Expression value,
                                                   SourcePosition pos) {
            return new CaseExpression(Optional.empty(),
                    List.of(new WhenClause(predicate, value, pos)),
                    Optional.empty(), pos);
        }
    }
}
