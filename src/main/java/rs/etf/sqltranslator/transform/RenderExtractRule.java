package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.ExtractExpression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code EXTRACT(field FROM source)} rendering for the target dialect.
 * <ul>
 *   <li>PostgreSQL / MySQL — native {@code EXTRACT}.</li>
 *   <li>T-SQL — {@code DATEPART} for calendar fields; {@code EPOCH} →
 *       {@code DATEDIFF(SECOND, '19700101', source)}; {@code DOW}/{@code ISO*} and
 *       other unmapped fields refused here (fall-through), not in
 *       {@link ValidateTargetCapabilitiesRule} (§2.4).</li>
 * </ul>
 */
public final class RenderExtractRule implements Rule {

    private static final Set<String> DATEPART_FIELDS = Set.of(
            "YEAR", "MONTH", "DAY", "HOUR", "MINUTE", "SECOND", "QUARTER", "WEEK");

    @Override
    public String name() {
        return "render-extract";
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
        public Object visitExtractExpression(ExtractExpression node) {
            ExtractExpression extract = (ExtractExpression) super.visitExtractExpression(node);
            if (ctx.target() != Dialect.TSQL) {
                return extract;
            }
            String field = extract.field();
            if (DATEPART_FIELDS.contains(field)) {
                return datePart(field, extract.source(), extract.pos());
            }
            if (field.equals("EPOCH")) {
                return epochDateDiff(extract.source(), extract.pos());
            }
            throw new UnsupportedFeatureException(
                    "EXTRACT(" + field + ") to T-SQL", extract.pos());
        }

        private static FunctionCall datePart(String field, Expression source,
                                             SourcePosition pos) {
            return new FunctionCall("DATEPART",
                    List.of(unitRef(field, pos), source),
                    false, Optional.empty(), Optional.empty(), pos);
        }

        private static FunctionCall epochDateDiff(Expression source, SourcePosition pos) {
            return new FunctionCall("DATEDIFF",
                    List.of(unitRef("SECOND", pos),
                            new StringLiteral("19700101", false, pos),
                            source),
                    false, Optional.empty(), Optional.empty(), pos);
        }

        private static ColumnRef unitRef(String unit, SourcePosition pos) {
            Identifier id = new Identifier(unit, false, pos);
            return new ColumnRef(new QualifiedName(List.of(id), pos), pos);
        }
    }
}
