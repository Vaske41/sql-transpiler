package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.DeleteStatement;
import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.OutputClause;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectItem;
import rs.etf.sqltranslator.ast.SelectStar;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Maps PostgreSQL {@code RETURNING} and T-SQL {@code OUTPUT} across dialects.
 * MySQL has neither — refuse. Refuse T-SQL {@code OUTPUT *} on UPDATE toward
 * PostgreSQL (semantic mismatch: T-SQL spans INSERTED and DELETED). Refuse
 * {@code OUTPUT DELETED.col} on UPDATE toward PostgreSQL (RETURNING is new-row).
 * Refuse non-column RETURNING expressions toward T-SQL (need {@code INSERTED.}/{@code DELETED.}).
 */
public final class RewriteReturningRule implements Rule {

    private enum DmlKind { INSERT, UPDATE, DELETE }

    @Override
    public String name() {
        return "rewrite-returning";
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
            Optional<OutputClause> output = reshape(rebuilt.outputClause(), DmlKind.INSERT);
            return new InsertStatement(rebuilt.table(), rebuilt.columns(), rebuilt.rows(),
                    rebuilt.query(), rebuilt.upsert(), output, rebuilt.pos());
        }

        @Override
        public Object visitUpdateStatement(UpdateStatement node) {
            UpdateStatement rebuilt = (UpdateStatement) super.visitUpdateStatement(node);
            Optional<OutputClause> output = reshape(rebuilt.outputClause(), DmlKind.UPDATE);
            return new UpdateStatement(rebuilt.ctes(), rebuilt.recursive(), rebuilt.table(),
                    rebuilt.alias(), rebuilt.assignments(), output, rebuilt.from(),
                    rebuilt.where(), rebuilt.pos());
        }

        @Override
        public Object visitDeleteStatement(DeleteStatement node) {
            DeleteStatement rebuilt = (DeleteStatement) super.visitDeleteStatement(node);
            Optional<OutputClause> output = reshape(rebuilt.outputClause(), DmlKind.DELETE);
            return new DeleteStatement(rebuilt.table(), rebuilt.alias(), output,
                    rebuilt.usingClause(), rebuilt.where(), rebuilt.pos());
        }

        private Optional<OutputClause> reshape(Optional<OutputClause> output, DmlKind kind) {
            if (output.isEmpty()) {
                return output;
            }
            if (ctx.target() == Dialect.MYSQL) {
                throw new UnsupportedFeatureException(
                        "RETURNING is not supported by " + ctx.target(), output.get().pos());
            }
            if (ctx.source() == ctx.target()) {
                return output;
            }
            if (ctx.target() == Dialect.TSQL) {
                String prefix = kind == DmlKind.DELETE ? "DELETED" : "INSERTED";
                return Optional.of(new OutputClause(
                        qualifyItems(output.get().items(), prefix, output.get().pos()),
                        output.get().pos()));
            }
            if (ctx.target() == Dialect.POSTGRESQL) {
                if (kind == DmlKind.UPDATE && containsStar(output.get().items())) {
                    throw new UnsupportedFeatureException(
                            "OUTPUT * on UPDATE has no faithful PostgreSQL RETURNING form",
                            output.get().pos());
                }
                return Optional.of(new OutputClause(
                        stripQualifiers(output.get().items(), kind, output.get().pos()),
                        output.get().pos()));
            }
            return output;
        }

        private static boolean containsStar(List<SelectItem> items) {
            return items.stream().anyMatch(SelectStar.class::isInstance);
        }

        private static List<SelectItem> qualifyItems(List<SelectItem> items, String prefix,
                                                     SourcePosition pos) {
            List<SelectItem> result = new ArrayList<>(items.size());
            for (SelectItem item : items) {
                if (item instanceof SelectStar star) {
                    Identifier table = new Identifier(prefix, false, pos);
                    result.add(new SelectStar(
                            Optional.of(new QualifiedName(List.of(table), pos)), star.pos()));
                } else if (item instanceof SelectExpr expr) {
                    result.add(new SelectExpr(qualifyExpr(expr.expr(), prefix, expr.pos()),
                            expr.alias(), expr.pos()));
                } else {
                    result.add(item);
                }
            }
            return result;
        }

        private static Expression qualifyExpr(Expression expr, String prefix, SourcePosition pos) {
            if (expr instanceof ColumnRef ref) {
                if (ref.name().parts().size() == 1) {
                    Identifier table = new Identifier(prefix, false, pos);
                    List<Identifier> parts = List.of(table, ref.name().parts().get(0));
                    return new ColumnRef(new QualifiedName(parts, pos), ref.pos());
                }
                return ref;
            }
            throw new UnsupportedFeatureException(
                    "RETURNING expression requires a column reference for T-SQL OUTPUT", pos);
        }

        private static List<SelectItem> stripQualifiers(List<SelectItem> items, DmlKind kind,
                                                        SourcePosition pos) {
            List<SelectItem> result = new ArrayList<>(items.size());
            for (SelectItem item : items) {
                if (item instanceof SelectStar star) {
                    result.add(new SelectStar(Optional.empty(), star.pos()));
                } else if (item instanceof SelectExpr expr) {
                    result.add(new SelectExpr(stripExpr(expr.expr(), kind, expr.pos()),
                            expr.alias(), expr.pos()));
                } else {
                    result.add(item);
                }
            }
            return result;
        }

        private static Expression stripExpr(Expression expr, DmlKind kind, SourcePosition pos) {
            if (expr instanceof ColumnRef ref && ref.name().parts().size() == 2) {
                String qualifier = ref.name().parts().get(0).value();
                if (qualifier.equalsIgnoreCase("DELETED") && kind == DmlKind.UPDATE) {
                    throw new UnsupportedFeatureException(
                            "OUTPUT DELETED on UPDATE has no faithful PostgreSQL RETURNING form",
                            pos);
                }
                if (qualifier.equalsIgnoreCase("INSERTED")
                        || qualifier.equalsIgnoreCase("DELETED")) {
                    Identifier col = ref.name().parts().get(1);
                    return new ColumnRef(new QualifiedName(List.of(col), pos), ref.pos());
                }
            }
            return expr;
        }
    }
}
