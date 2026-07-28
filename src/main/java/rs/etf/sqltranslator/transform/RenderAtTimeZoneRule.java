package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AtTimeZone;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

/**
 * Renders PostgreSQL {@code AT TIME ZONE} for non-PostgreSQL targets.
 * T-SQL prints natively. MySQL has no faithful form ({@code CONVERT_TZ} with
 * {@code @@session.time_zone} is not equivalent) — refuse.
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
            if (ctx.target() == Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "AT TIME ZONE is not supported by MySQL", rebuilt.pos());
            }
            return rebuilt;
        }
    }
}
