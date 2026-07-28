package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStandardScalarTest {

    private String translate(String sql, Dialect from, Dialect to) {
        return Translator.translate(sql, from, to).sql();
    }

    @Test
    void substringFromForIsNotMistakenForExtract() {
        assertThat(translate("SELECT SUBSTRING(v FROM 1 FOR 3) FROM cars",
                Dialect.POSTGRESQL, Dialect.MYSQL))
                .containsIgnoringCase("SUBSTRING");
    }

    @Test
    void positionInIsTranslated() {
        assertThat(translate("SELECT POSITION(' ' IN name) FROM t",
                Dialect.POSTGRESQL, Dialect.TSQL))
                .containsIgnoringCase("CHARINDEX");
    }

    @Test
    void mysqlDisplayWidthIsDroppedNotRefused() {
        assertThat(translate("CREATE TABLE t (a INT(11) NOT NULL)",
                Dialect.MYSQL, Dialect.POSTGRESQL))
                .containsIgnoringCase("INTEGER").doesNotContain("(11)");
    }
}
