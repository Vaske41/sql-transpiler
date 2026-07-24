package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.Query;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CteBuildTest {

    @Test
    void recursiveCteKeywordBuilds() throws Exception {
        String sql = Files.readString(Path.of(
                "src/test/resources/cases/cte-recursive/keyword/input.postgresql.sql"));
        Script script = AstBuilderFacade.buildScript(sql, Dialect.POSTGRESQL);
        Query query = ((SelectStatement) script.statements().get(0)).query();
        assertThat(query.recursive()).isTrue();
        assertThat(query.ctes()).hasSize(1);
    }

    @Test
    void recursiveCteSelfReferenceBuilds() throws Exception {
        String sql = Files.readString(Path.of(
                "src/test/resources/cases/cte-recursive/self-ref/input.tsql.sql"));
        Script script = AstBuilderFacade.buildScript(sql, Dialect.TSQL);
        Query query = ((SelectStatement) script.statements().get(0)).query();
        assertThat(query.recursive()).isTrue();
        assertThat(query.ctes()).hasSize(1);
    }
}
