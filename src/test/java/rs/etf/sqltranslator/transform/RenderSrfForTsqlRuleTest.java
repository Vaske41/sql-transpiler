package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderSrfForTsqlRuleTest {

    private String toTsql(String sql) {
        return Translator.translate(sql, Dialect.POSTGRESQL, Dialect.TSQL).sql();
    }

    @Test
    void jsonArrayElementsRefusedTowardTsql() {
        assertThatThrownBy(() -> toTsql("SELECT t.value FROM json_array_elements(e.events) AS t"))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("OPENJSON value shape mismatch");
    }

    @Test
    void stringToTableBecomesStringSplit() {
        assertThat(toTsql("SELECT s.value FROM string_to_table(e.tags, ',') AS s"))
                .containsIgnoringCase("STRING_SPLIT");
    }

    @Test
    void jsonArrayElementsTextBecomesOpenJson() {
        assertThat(toTsql("SELECT t.value FROM json_array_elements_text(e.events) AS t"))
                .containsIgnoringCase("OPENJSON");
    }

    @Test
    void unnestStringToArrayBecomesStringSplit() {
        assertThat(toTsql("SELECT * FROM unnest(string_to_array(s, ',')) AS t"))
                .containsIgnoringCase("STRING_SPLIT");
    }

    @Test
    void correlatedCommaJoinBecomesCrossApply() {
        String sql = toTsql(
                "SELECT t.value FROM events e, json_array_elements_text(e.payload) AS t");
        assertThat(sql).containsIgnoringCase("CROSS APPLY");
        assertThat(sql).containsIgnoringCase("OPENJSON");
        assertThat(sql.toUpperCase()).doesNotContain(", OPENJSON");
    }

    @Test
    void correlatedInnerJoinSrfBecomesCrossApply() {
        String sql = toTsql(
                "SELECT t.value FROM events e "
                        + "JOIN json_array_elements_text(e.payload) AS t ON true");
        assertThat(sql).containsIgnoringCase("CROSS APPLY");
        assertThat(sql).containsIgnoringCase("OPENJSON");
        assertThat(sql.toUpperCase()).doesNotContain("INNER JOIN OPENJSON");
        assertThat(sql.toUpperCase()).doesNotContain("JOIN OPENJSON");
    }

    @Test
    void unmappableSrfStillRefusesHonestly() {
        assertThatThrownBy(() -> toTsql("SELECT * FROM generate_subscripts(a, 1) AS g"))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("table function");
    }
}
