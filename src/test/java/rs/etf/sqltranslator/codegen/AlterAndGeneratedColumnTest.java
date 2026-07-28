package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.codegen.CodegenTestSupport.printTranslated;

class AlterAndGeneratedColumnTest {

    @Test
    void checkConstraintSurvivesAlter() {
        assertThat(printTranslated(
                "ALTER TABLE t ADD CONSTRAINT c CHECK (amount > 0)",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .containsIgnoringCase("CHECK");
    }

    @Test
    void generatedColumnCrossesDialects() {
        assertThat(printTranslated(
                "CREATE TABLE t (c VARCHAR(32) NOT NULL, n INT AS (CHAR_LENGTH(c)))",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql())
                .containsIgnoringCase("GENERATED ALWAYS AS");
    }
}
