package rs.etf.sqltranslator.transform;

import rs.etf.sqltranslator.ast.AstTransformer;
import rs.etf.sqltranslator.ast.Cte;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL Server does not allow a nested {@code WITH} inside a CTE body. When the
 * target is T-SQL, hoist nested CTE lists into a single flat {@code WITH} list
 * so printers emit valid SQL instead of golden-locked nested {@code WITH}.
 */
public final class FlattenNestedCtesForTsqlRule implements Rule {

    @Override
    public String name() {
        return "flatten-nested-ctes-for-tsql";
    }

    @Override
    public Script apply(Script script, TranslationContext ctx) {
        if (ctx.target() != Dialect.TSQL) {
            return script;
        }
        return new Flattener().transform(script);
    }

    private static final class Flattener extends AstTransformer {

        @Override
        public Object visitQuery(Query node) {
            Query rebuilt = (Query) super.visitQuery(node);
            if (rebuilt.ctes().isEmpty()) {
                return rebuilt;
            }
            List<Cte> flat = new ArrayList<>();
            for (Cte cte : rebuilt.ctes()) {
                Query body = cte.query();
                if (!body.ctes().isEmpty()) {
                    flat.addAll(body.ctes());
                    Query stripped = new Query(
                            List.of(),
                            body.first(),
                            body.unionArms(),
                            body.orderBy(),
                            body.limit(),
                            body.pos());
                    flat.add(new Cte(cte.name(), cte.columns(), stripped, cte.pos()));
                } else {
                    flat.add(cte);
                }
            }
            return new Query(
                    flat,
                    rebuilt.first(),
                    rebuilt.unionArms(),
                    rebuilt.orderBy(),
                    rebuilt.limit(),
                    rebuilt.pos());
        }
    }
}
