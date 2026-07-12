package rs.etf.sqltranslator.core;

import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.codegen.SqlPrinters;
import rs.etf.sqltranslator.parser.AstBuilderFacade;
import rs.etf.sqltranslator.transform.RuleEngine;
import rs.etf.sqltranslator.transform.TranslationResult;

/**
 * The Facade (ROADMAP Phase 1): parse → rule-rewrite → print. The Phase 6 CLI and
 * the Phase 7 benchmark harness call exactly this method and nothing deeper.
 *
 * @throws ParseException              on syntax errors (never partial output)
 * @throws UnsupportedFeatureException on refused constructs, source- or target-side
 */
public final class Translator {

    private Translator() {
    }

    public static TranslationOutput translate(String sql, Dialect source, Dialect target) {
        Script script = AstBuilderFacade.buildScript(sql, source);
        TranslationResult result = RuleEngine.standard().run(script, source, target);
        return new TranslationOutput(SqlPrinters.print(result.script(), target),
                result.report());
    }
}
