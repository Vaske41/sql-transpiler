package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.DropIndexStatement;
import rs.etf.sqltranslator.ast.DropRoutineStatement;
import rs.etf.sqltranslator.ast.DropViewStatement;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;
import java.util.Optional;

/**
 * Dialect reshape for cheap DROP forms:
 * <ul>
 *   <li>{@code DROP INDEX} — T-SQL/MySQL require {@code ON table}; PostgreSQL forbids it.
 *       Refuse when the target needs a table and none is known. Strip {@code IF EXISTS}
 *       toward MySQL (unsupported).</li>
 *   <li>{@code CASCADE} on VIEW/FUNCTION — PG-only; drop with warning elsewhere.</li>
 *   <li>{@code DROP FUNCTION (args)} — empty {@code ()} stripped toward MySQL/T-SQL;
 *       non-empty arg lists refused (overload disambiguation).</li>
 * </ul>
 */
public final class ReshapeDropDdlRule implements Rule {

    @Override
    public String name() {
        return "reshape-drop-ddl";
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
        public Object visitDropIndexStatement(DropIndexStatement node) {
            DropIndexStatement idx = (DropIndexStatement) super.visitDropIndexStatement(node);
            Dialect target = ctx.target();
            if (target == Dialect.POSTGRESQL) {
                return new DropIndexStatement(idx.name(), idx.ifExists(), Optional.empty(), idx.pos());
            }
            if (idx.table().isEmpty()) {
                throw new UnsupportedFeatureException(
                        "DROP INDEX without ON table (required by " + target + ")", idx.pos());
            }
            boolean ifExists = idx.ifExists();
            if (target == Dialect.MYSQL && ifExists) {
                ctx.report().warn("IF_EXISTS_DROPPED",
                        "DROP INDEX IF EXISTS is not supported by MySQL; IF EXISTS omitted",
                        idx.pos());
                ifExists = false;
            }
            return new DropIndexStatement(idx.name(), ifExists, idx.table(), idx.pos());
        }

        @Override
        public Object visitDropViewStatement(DropViewStatement node) {
            DropViewStatement view = (DropViewStatement) super.visitDropViewStatement(node);
            if (view.cascade() && ctx.target() != Dialect.POSTGRESQL) {
                ctx.report().warn("CASCADE_DROPPED",
                        "CASCADE is not supported by " + ctx.target() + " on DROP VIEW; omitted",
                        view.pos());
                return new DropViewStatement(view.name(), view.ifExists(), false, view.pos());
            }
            return view;
        }

        @Override
        public Object visitDropRoutineStatement(DropRoutineStatement node) {
            DropRoutineStatement fn = (DropRoutineStatement) super.visitDropRoutineStatement(node);
            if (fn.hasSignature() && !fn.argTypes().isEmpty()
                    && ctx.target() != Dialect.POSTGRESQL) {
                throw new UnsupportedFeatureException(
                        "DROP FUNCTION with argument types (overload) to " + ctx.target(),
                        fn.pos());
            }
            boolean cascade = fn.cascade();
            if (cascade && ctx.target() != Dialect.POSTGRESQL) {
                ctx.report().warn("CASCADE_DROPPED",
                        "CASCADE is not supported by " + ctx.target() + " on DROP FUNCTION; omitted",
                        fn.pos());
                cascade = false;
            }
            boolean hasSignature = fn.hasSignature();
            List<?> argTypes = fn.argTypes();
            if (hasSignature && argTypes.isEmpty() && ctx.target() != Dialect.POSTGRESQL) {
                hasSignature = false;
            }
            return new DropRoutineStatement(fn.name(), fn.ifExists(), cascade, hasSignature,
                    fn.argTypes(), fn.pos());
        }
    }
}
