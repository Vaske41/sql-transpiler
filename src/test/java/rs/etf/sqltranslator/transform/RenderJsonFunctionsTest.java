package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class RenderJsonFunctionsTest {

    @Test
    void jsonBuildObjectBecomesMysqlJsonObject() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT json_build_object('a', x) FROM t",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .containsIgnoringCase("JSON_OBJECT");
    }

    @Test
    void jsonContainmentIsFaithfulForMysql() {
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT * FROM t WHERE c @> '[1]'",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .containsIgnoringCase("JSON_CONTAINS");
    }
}
