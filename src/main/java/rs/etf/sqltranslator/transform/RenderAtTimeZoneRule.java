package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AtTimeZone;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;
import java.util.Optional;

/**
 * Renders PostgreSQL {@code AT TIME ZONE} for non-PostgreSQL targets.
 * T-SQL prints natively; MySQL maps to {@code CONVERT_TZ} when the zone is an
 * explicit string literal.
 */
public final class RenderAtTimeZoneRule implements Rule {

    @Override
    public String name() {
        return "render-at-time-zone";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() == Dialect.POSTGRESQL) {
            return script;
        }
        return new Rewriter(ctx).transform(script);
    }

    private static final class Rewriter extends AstTransformer {

        private final TranslationContext ctx;

        private Rewriter(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitAtTimeZone(AtTimeZone node) {
            AtTimeZone rebuilt = (AtTimeZone) super.visitAtTimeZone(node);
            if (ctx.target() == Dialect.TSQL) {
                return rebuilt;
            }
            if (ctx.target() != Dialect.MYSQL) {
                return rebuilt;
            }
            if (!(rebuilt.zone() instanceof StringLiteral zoneLit)) {
                throw new UnsupportedFeatureException(
                        "AT TIME ZONE to MySQL requires a literal zone", rebuilt.pos());
            }
            Expression sessionTz = new ColumnRef(
                    new QualifiedName(List.of(new Identifier("@@session.time_zone", false,
                            rebuilt.pos())), rebuilt.pos()),
                    rebuilt.pos());
            return new FunctionCall(
                    "CONVERT_TZ",
                    List.of(rebuilt.value(), sessionTz, zoneLit),
                    false,
                    Optional.empty(),
                    Optional.empty(),
                    rebuilt.pos());
        }
    }
}
