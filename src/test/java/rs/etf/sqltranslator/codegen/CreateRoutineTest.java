package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static rs.etf.sqltranslator.codegen.CodegenTestSupport.printTranslated;

class CreateRoutineTest {

    @Test
    void singleStatementFunctionBodyTranslates() {
        assertThat(printTranslated(
                "CREATE FUNCTION f() RETURNS INT AS $$ SELECT COUNT(*) FROM t $$ LANGUAGE sql",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .containsIgnoringCase("CREATE FUNCTION")
                .containsIgnoringCase("RETURNS INT")
                .containsIgnoringCase("BEGIN")
                .containsIgnoringCase("RETURN (SELECT COUNT(*) FROM t)")
                .containsIgnoringCase("END")
                .doesNotContain("$$")
                .doesNotContainIgnoringCase("LANGUAGE");
    }

    @Test
    void proceduralBodyIsRefusedNotGuessed() {
        assertThatThrownBy(() -> printTranslated(
                "CREATE FUNCTION f() RETURNS TRIGGER AS $$ BEGIN "
                        + "IF NEW.a <> OLD.a THEN INSERT INTO log VALUES (1); END IF; "
                        + "RETURN NEW; END $$ LANGUAGE plpgsql",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("procedural");
    }
}
