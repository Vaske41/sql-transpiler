package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.FunctionCall;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WindowBuildTest {

    @Test
    void windowFrameBuildsAndPrints() throws Exception {
        String sql = Files.readString(Path.of(
                "src/test/resources/cases/windows/frame-rows/input.postgresql.sql"));
        Script script = AstBuilderFacade.buildScript(sql, Dialect.POSTGRESQL);
        SelectStatement select = (SelectStatement) script.statements().get(0);
        SelectExpr item = (SelectExpr) select.query().first().items().get(0);
        FunctionCall call = (FunctionCall) item.expr();
        assertThat(call.window()).isPresent();
        assertThat(call.window().get().frame()).isPresent();

        String out = CodegenTestSupport.printTranslated(
                sql, Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(out).containsIgnoringCase("ROWS");
        assertThat(out).containsIgnoringCase("UNBOUNDED PRECEDING");
        assertThat(out).containsIgnoringCase("CURRENT ROW");
    }
}
