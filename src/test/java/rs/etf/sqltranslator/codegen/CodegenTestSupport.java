package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.Expression;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.TranslationOutput;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import java.util.Set;

/**
 * Codegen test plumbing: SQL snippet → AST fragments; shared refusal manifest;
 * through-rules print helper for pipeline contract tests.
 */
public final class CodegenTestSupport {

    /**
     * Direction-refusal manifest — single source of truth for corpus / golden /
     * rule-engine suite filters.
     */
    public static final Set<String> EXPECTED_REFUSALS = Set.of(
            "joins/full-join/input.tsql.sql|MYSQL",
            "joins/full-join/input.postgresql.sql|MYSQL",
            "limits/limit-offset/input.mysql.sql|TSQL",
            "limits/union-limit-no-order/input.postgresql.sql|TSQL",
            "limits/union-limit-no-order/input.mysql.sql|TSQL",
            "aggregates-ordered/array-agg-ordered/input.postgresql.sql|MYSQL",
            "aggregates-ordered/array-agg-ordered/input.postgresql.sql|TSQL",
            "aggregates-ordered/group-concat-ordered/input.mysql.sql|TSQL",
            "casts/pg-array-cast/input.postgresql.sql|MYSQL",
            "casts/pg-array-cast/input.postgresql.sql|TSQL",
            "json-access/json-containment/input.postgresql.sql|MYSQL",
            "json-access/json-containment/input.postgresql.sql|TSQL",
            "interval/pg-string/input.postgresql.sql|TSQL",
            "interval/mysql-numeric/input.mysql.sql|TSQL",
            "interval/mysql-string-unit/input.mysql.sql|TSQL",
            "interval/interval-compound/input.postgresql.sql|MYSQL",
            "interval/interval-compound/input.postgresql.sql|TSQL",
            "ddl-misc/drop-index-no-table/input.postgresql.sql|MYSQL",
            "ddl-misc/drop-index-no-table/input.postgresql.sql|TSQL",
            "ddl-misc/drop-function-args/input.postgresql.sql|MYSQL",
            "ddl-misc/drop-function-args/input.postgresql.sql|TSQL");

    private CodegenTestSupport() {
    }

    /** Corpus walker predicate shared by golden / round-trip / rule-engine suites. */
    public static boolean isCorpusInput(java.nio.file.Path path) {
        String normalized = path.toString().replace('\\', '/');
        return path.getFileName().toString().startsWith("input.")
                && !normalized.contains("/unsupported/")
                && !normalized.contains("/semantic/");
    }

    static Expression expr(String selectSql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(selectSql, dialect);
        SelectStatement select = (SelectStatement) script.statements().get(0);
        return ((SelectExpr) select.query().first().items().get(0)).expr();
    }

    static Expression where(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        return ((SelectStatement) script.statements().get(0))
                .query().first().where().orElseThrow();
    }

    /** Parse → rule engine → print — the real pipeline printers must survive. */
    public static TranslationOutput printTranslated(String sql, Dialect source, Dialect target) {
        return Translator.translate(sql, source, target);
    }
}
