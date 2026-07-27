package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;

import static org.assertj.core.api.Assertions.assertThat;

class TypeFoldTest {

    private String translate(String sql, Dialect from, Dialect to) {
        return Translator.translate(sql, from, to).sql();
    }

    @Test
    void mysqlSignedCastFoldsToBigintForPostgres() {
        assertThat(translate("SELECT CAST(x AS SIGNED) FROM t", Dialect.MYSQL, Dialect.POSTGRESQL))
                .containsIgnoringCase("CAST(x AS BIGINT)");
    }

    @Test
    void mysqlUnsignedCastWidensToDecimal() {
        assertThat(translate("SELECT CAST(x AS UNSIGNED) FROM t", Dialect.MYSQL, Dialect.POSTGRESQL))
                .containsIgnoringCase("DECIMAL(20");
    }

    @Test
    void twoWordUnsignedColumnTypeIsAccepted() {
        assertThat(translate("CREATE TABLE t (a INT UNSIGNED NOT NULL)",
                Dialect.MYSQL, Dialect.POSTGRESQL))
                .containsIgnoringCase("a ");
    }
}
