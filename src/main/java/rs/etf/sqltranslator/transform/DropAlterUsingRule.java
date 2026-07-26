package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AlterColumnType;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.Optional;

/**
 * {@code USING expr} on ALTER COLUMN type changes is PostgreSQL-only. Strip it toward
 * MySQL / T-SQL with a warning so the type change still emits valid target SQL.
 */
public final class DropAlterUsingRule implements Rule {

    @Override
    public String name() {
        return "drop-alter-using";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() == Dialect.POSTGRESQL) {
            return script;
        }
        return new Dropper(ctx).transform(script);
    }

    private static final class Dropper extends AstTransformer {

        private final TranslationContext ctx;

        private Dropper(TranslationContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Object visitAlterColumnType(AlterColumnType node) {
            AlterColumnType altered = (AlterColumnType) super.visitAlterColumnType(node);
            if (altered.using().isEmpty()) {
                return altered;
            }
            ctx.report().warn("USING_DROPPED",
                    "USING expression is not supported by " + ctx.target() + "; omitted",
                    altered.pos());
            return new AlterColumnType(altered.column(), altered.type(), Optional.empty(),
                    altered.pos());
        }
    }
}
