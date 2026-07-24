package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.Assignment;
import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Identifier;
import rs.etf.sqltranslator.ast.Join;
import rs.etf.sqltranslator.ast.QualifiedName;
import rs.etf.sqltranslator.ast.Relation;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.TableRef;
import rs.etf.sqltranslator.ast.TableSource;
import rs.etf.sqltranslator.ast.UpdateStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MySQL has no {@code UPDATE … SET … FROM}. Rewrite toward MySQL by qualifying SET
 * left-hand sides with the target table/alias so the printer can emit the faithful
 * multi-table comma-join form ({@code UPDATE t, src SET t.c = … WHERE …}).
 * Refuses only self-join updates (target table reappears as a top-level FROM
 * relation) and LATERAL sources MySQL cannot place in the update list.
 */
public final class RewriteUpdateFromForMysqlRule implements Rule {

    @Override
    public String name() {
        return "rewrite-update-from-for-mysql";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.MYSQL) {
            return script;
        }
        return new Rewriter().transform(script);
    }

    private static final class Rewriter extends AstTransformer {

        @Override
        public Object visitUpdateStatement(UpdateStatement node) {
            UpdateStatement updated = (UpdateStatement) super.visitUpdateStatement(node);
            if (updated.from().isEmpty()) {
                return updated;
            }
            TableSource from = updated.from().get();
            refuseIfUnsupported(updated.table(), from, updated.pos());
            Identifier qualifier = updated.alias().orElseGet(() -> updated.table().last());
            List<Assignment> qualified = new ArrayList<>(updated.assignments().size());
            for (Assignment a : updated.assignments()) {
                qualified.add(qualify(a, qualifier));
            }
            return new UpdateStatement(updated.table(), updated.alias(),
                    List.copyOf(qualified), updated.from(), updated.where(), updated.pos());
        }

        private static Assignment qualify(Assignment a, Identifier qualifier) {
            QualifiedName col = a.column();
            if (col.parts().size() > 1) {
                return a; // already qualified
            }
            List<Identifier> parts = List.of(qualifier, col.last());
            return new Assignment(new QualifiedName(parts, col.pos()), a.value(), a.pos());
        }

        private static void refuseIfUnsupported(QualifiedName target, TableSource from,
                                                rs.etf.sqltranslator.core.SourcePosition pos) {
            String targetKey = target.last().value().toLowerCase(Locale.ROOT);
            for (Relation rel : topLevelRelations(from)) {
                if (rel instanceof TableRef ref) {
                    String name = ref.table().last().value().toLowerCase(Locale.ROOT);
                    if (name.equals(targetKey)) {
                        throw new UnsupportedFeatureException(
                                "UPDATE ... FROM self-join to MySQL", pos);
                    }
                }
                // Future LATERAL relation kinds refuse here when added to the AST.
            }
        }

        private static List<Relation> topLevelRelations(TableSource from) {
            List<Relation> rels = new ArrayList<>();
            rels.add(from.first());
            for (Join join : from.joins()) {
                rels.add(join.table());
            }
            return rels;
        }
    }
}
