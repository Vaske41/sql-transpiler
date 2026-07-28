package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RewriteReturningRuleTest {

    @Test
    void pgReturningBecomesTsqlOutputInserted() {
        assertThat(Translator.translate(
                "INSERT INTO t (a) VALUES (1) RETURNING id", Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .containsIgnoringCase("OUTPUT INSERTED.id");
    }

    @Test
    void tsqlOutputBecomesPgReturning() {
        assertThat(Translator.translate(
                "INSERT INTO t (a) OUTPUT INSERTED.id VALUES (1)", Dialect.TSQL, Dialect.POSTGRESQL).sql())
                .containsIgnoringCase("RETURNING");
    }

    @Test
    void returningStillRefusesForMysql() {
        assertThatThrownBy(() -> Translator.translate(
                "INSERT INTO t (a) VALUES (1) RETURNING id", Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("RETURNING");
    }
}
