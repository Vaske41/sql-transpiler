package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.Upsert;
import rs.etf.sqltranslator.ast.UpsertKind;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.Optional;

/**
 * Faithful upsert reshaping:
 * <ul>
 *   <li>PostgreSQL {@code ON CONFLICT … DO UPDATE} → MySQL {@code ON DUPLICATE KEY UPDATE}
 *       when there is no UPDATE WHERE clause (MySQL cannot express it).</li>
 *   <li>{@code ON CONFLICT DO NOTHING} toward MySQL/T-SQL → refuse (no faithful form).</li>
 *   <li>Any upsert toward T-SQL → refuse (MERGE would need schema/keys we may not have).</li>
 *   <li>MySQL {@code ON DUPLICATE KEY} toward PostgreSQL → refuse (conflict target unknown).</li>
 *   <li>{@code RETURNING} toward MySQL/T-SQL → refuse.</li>
 * </ul>
 */
public final class RewriteUpsertRule implements Rule {

    @Override
    public String name() {
        return "rewrite-upsert";
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
        public Object visitInsertStatement(InsertStatement node) {
            InsertStatement rebuilt = (InsertStatement) super.visitInsertStatement(node);
            if (rebuilt.returning().isPresent()
                    && (ctx.target() == Dialect.MYSQL || ctx.target() == Dialect.TSQL)) {
                throw new UnsupportedFeatureException(
                        "RETURNING is not supported by " + ctx.target(), rebuilt.pos());
            }
            if (rebuilt.upsert().isEmpty()) {
                return rebuilt;
            }
            Upsert upsert = rebuilt.upsert().get();
            Optional<Upsert> reshaped = reshape(upsert);
            return new InsertStatement(rebuilt.table(), rebuilt.columns(), rebuilt.rows(),
                    rebuilt.query(), reshaped, rebuilt.returning(), rebuilt.pos());
        }

        private Optional<Upsert> reshape(Upsert upsert) {
            if (ctx.target() == Dialect.TSQL) {
                throw new UnsupportedFeatureException(
                        "INSERT upsert (ON CONFLICT / ON DUPLICATE KEY) is not supported by T-SQL",
                        upsert.pos());
            }
            return switch (upsert.kind()) {
                case ON_CONFLICT_UPDATE -> reshapeConflictUpdate(upsert);
                case ON_CONFLICT_NOTHING -> reshapeConflictNothing(upsert);
                case ON_DUPLICATE_KEY -> reshapeDuplicate(upsert);
            };
        }

        private Optional<Upsert> reshapeConflictUpdate(Upsert upsert) {
            if (ctx.target() == Dialect.POSTGRESQL) {
                return Optional.of(upsert);
            }
            // MySQL
            if (upsert.where().isPresent()) {
                throw new UnsupportedFeatureException(
                        "ON CONFLICT DO UPDATE WHERE is not supported by MySQL",
                        upsert.pos());
            }
            return Optional.of(new Upsert(UpsertKind.ON_DUPLICATE_KEY, java.util.List.of(),
                    upsert.assignments(), Optional.empty(), upsert.pos()));
        }

        private Optional<Upsert> reshapeConflictNothing(Upsert upsert) {
            if (ctx.target() == Dialect.POSTGRESQL) {
                return Optional.of(upsert);
            }
            throw new UnsupportedFeatureException(
                    "ON CONFLICT DO NOTHING has no faithful MySQL form", upsert.pos());
        }

        private Optional<Upsert> reshapeDuplicate(Upsert upsert) {
            if (ctx.target() == Dialect.MYSQL) {
                return Optional.of(upsert);
            }
            // PostgreSQL needs a conflict target we do not know from ON DUPLICATE KEY alone.
            throw new UnsupportedFeatureException(
                    "ON DUPLICATE KEY UPDATE has no conflict target for PostgreSQL",
                    upsert.pos());
        }
    }
}
