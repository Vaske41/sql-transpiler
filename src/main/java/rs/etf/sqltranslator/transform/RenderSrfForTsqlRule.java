package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.BooleanLiteral;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.JoinKind;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Relation;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.TableFunction;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * PostgreSQL set-returning functions in FROM position → T-SQL {@code OPENJSON} /
 * {@code STRING_SPLIT}. Owns the T-SQL SRF fall-through (validator must not refuse
 * table functions toward T-SQL, or this rewrite never runs).
 *
 * <p>Correlated TVF joins become {@code CROSS APPLY} / {@code OUTER APPLY}
 * ({@code Join.lateral = true}) — T-SQL cannot plain-JOIN a TVF that references the
 * left side. INNER + trivial ON folds to CROSS APPLY; LEFT + trivial ON becomes
 * OUTER APPLY. Non-foldable correlated shapes refuse.
 */
public final class RenderSrfForTsqlRule implements Rule {

    private static final Set<String> JSON_ARRAY_SRF = Set.of(
            "JSON_ARRAY_ELEMENTS", "JSONB_ARRAY_ELEMENTS",
            "JSON_ARRAY_ELEMENTS_TEXT", "JSONB_ARRAY_ELEMENTS_TEXT");

    private static final Set<String> STRING_SPLIT_SRF = Set.of(
            "STRING_TO_TABLE", "STRING_TO_ARRAY");

    private static final Set<String> TSQL_TVF = Set.of("OPENJSON", "STRING_SPLIT");

    @Override
    public String name() {
        return "render-srf-tsql";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.TSQL) {
            return script;
        }
        return new Renderer().transform(script);
    }

    private static final class Renderer extends AstTransformer {

        @Override
        public Object visitJoin(Join node) {
            Relation table = rebuild(node.table());
            Optional<Expression> on = rebuildOptional(node.on());
            List<Identifier> usingColumns = rebuildList(node.usingColumns());
            boolean lateral = node.lateral();
            JoinKind kind = node.kind();

            // PG SRFs are implicitly lateral; T-SQL needs APPLY when the TVF refs the left.
            if (table instanceof TableFunction tf
                    && isTsqlTvf(tf)
                    && argsContainColumnRef(tf)) {
                if (kind == JoinKind.CROSS && usingColumns.isEmpty()) {
                    lateral = true;
                    on = Optional.empty();
                } else if (kind == JoinKind.INNER
                        && usingColumns.isEmpty()
                        && isTrivialOn(on)) {
                    kind = JoinKind.CROSS;
                    lateral = true;
                    on = Optional.empty();
                } else if (kind == JoinKind.LEFT
                        && usingColumns.isEmpty()
                        && isTrivialOn(on)) {
                    lateral = true;
                } else {
                    throw new UnsupportedFeatureException(
                            "correlated table function join cannot fold to APPLY",
                            node.pos());
                }
            }
            return new Join(kind, table, on, usingColumns, lateral, node.pos());
        }

        @Override
        public Object visitTableFunction(TableFunction node) {
            String fn = node.name().last().value().toUpperCase(Locale.ROOT);

            // Already a T-SQL TVF (e.g. T-SQL → T-SQL identity) — leave alone.
            if (TSQL_TVF.contains(fn)) {
                return super.visitTableFunction(node);
            }

            if (JSON_ARRAY_SRF.contains(fn) && node.args().size() == 1) {
                // OPENJSON's default value column is unquoted text — matches *_TEXT
                // faithfully; non-_TEXT still maps (brief) with that known semantic gap.
                return rename(node, "OPENJSON", rebuildList(node.args()));
            }

            if (STRING_SPLIT_SRF.contains(fn) && node.args().size() == 2) {
                return rename(node, "STRING_SPLIT", rebuildList(node.args()));
            }

            if ("UNNEST".equals(fn) && node.args().size() == 1) {
                Expression arg = rebuild(node.args().get(0));
                if (arg instanceof FunctionCall call
                        && "STRING_TO_ARRAY".equals(call.name())
                        && call.args().size() == 2) {
                    return rename(node, "STRING_SPLIT", call.args());
                }
                throw new UnsupportedFeatureException(
                        "table function UNNEST (no T-SQL form for array column)",
                        node.pos());
            }

            throw new UnsupportedFeatureException(
                    "table function " + fn + " (no T-SQL form)", node.pos());
        }

        private static TableFunction rename(TableFunction node, String name,
                                           List<Expression> args) {
            SourcePosition pos = node.pos();
            return new TableFunction(
                    new QualifiedName(List.of(new Identifier(name, false, pos)), pos),
                    args,
                    node.alias(),
                    node.columnAliases(),
                    List.of(),
                    pos);
        }

        private static boolean isTsqlTvf(TableFunction tf) {
            return TSQL_TVF.contains(tf.name().last().value().toUpperCase(Locale.ROOT));
        }

        /** Empty / TRUE / {@code 1} ON — foldable to APPLY (no residual predicate). */
        private static boolean isTrivialOn(Optional<Expression> on) {
            if (on.isEmpty()) {
                return true;
            }
            Expression expr = on.get();
            if (expr instanceof BooleanLiteral b) {
                return b.value();
            }
            return expr instanceof NumericLiteral n && "1".equals(n.text());
        }

        private static boolean argsContainColumnRef(TableFunction tf) {
            boolean[] hit = {false};
            class Probe extends AstTransformer {
                @Override
                public Object visitColumnRef(ColumnRef node) {
                    hit[0] = true;
                    return super.visitColumnRef(node);
                }

                void scan(Expression expr) {
                    rebuild(expr);
                }
            }
            Probe probe = new Probe();
            for (Expression arg : tf.args()) {
                probe.scan(arg);
                if (hit[0]) {
                    return true;
                }
            }
            return false;
        }
    }
}
