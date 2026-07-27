package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenderSrfForMysqlRuleTest {

    private String toMysql(String sql) {
        return Translator.translate(sql, Dialect.POSTGRESQL, Dialect.MYSQL).sql();
    }

    @Test
    void jsonArrayElementsBecomesJsonTable() {
        assertThat(toMysql("SELECT t.value FROM json_array_elements(e.events) AS t"))
                .containsIgnoringCase("JSON_TABLE")
                .containsIgnoringCase("'$[*]'")
                .containsIgnoringCase("COLUMNS");
    }

    @Test
    void jsonArrayElementsTextUsesTextColumn() {
        String sql = toMysql("SELECT t.value FROM json_array_elements_text(e.events) AS t");
        assertThat(sql)
                .containsIgnoringCase("JSON_TABLE")
                .containsIgnoringCase("COLUMNS");
        assertThat(sql.toUpperCase()).contains("TEXT");
        assertThat(sql.toUpperCase()).doesNotContain("VALUE JSON PATH");
    }

    @Test
    void unnestStringToArrayBecomesJsonTable() {
        assertThat(toMysql("SELECT * FROM unnest(string_to_array(s, ',')) AS t"))
                .containsIgnoringCase("JSON_TABLE")
                .containsIgnoringCase("COLUMNS");
    }

    @Test
    void unnestStringToArrayEscapesQuotesAndBackslashes() {
        // Data with ", \, and delimiter must become a JSON-safe CONCAT/REPLACE chain:
        // REPLACE(REPLACE(s, '\', '\\'), '"', '\"') before delimiter → '","'.
        String sql = toMysql(
                "SELECT * FROM unnest(string_to_array('a\"b,c\\d,e', ',')) AS t");
        String upper = sql.toUpperCase();
        assertThat(upper).contains("JSON_TABLE");
        assertThat(upper).contains("REPLACE");
        assertThat(sql).contains("\\\\");
        assertThat(sql).contains("\\\"");
        assertThat(sql).contains("\",\"");
    }

    @Test
    void unnestStringToArrayKeepsDelimiterInPayloadAsSplit() {
        // Comma delimiter with multi-element payload — encoding still wraps JSON_TABLE TEXT.
        String sql = toMysql(
                "SELECT * FROM unnest(string_to_array(payload, ',')) AS t");
        assertThat(sql)
                .containsIgnoringCase("JSON_TABLE")
                .containsIgnoringCase("CONCAT")
                .containsIgnoringCase("REPLACE");
    }

    @Test
    void unnestStringToArrayRefusesQuoteOrBackslashDelimiter() {
        assertThatThrownBy(() -> toMysql("SELECT * FROM unnest(string_to_array(s, '\"')) AS t"))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("unnest(string_to_array)")
                .hasMessageContaining("delimiter");
        assertThatThrownBy(() -> toMysql("SELECT * FROM unnest(string_to_array(s, E'\\\\')) AS t"))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("unnest(string_to_array)")
                .hasMessageContaining("delimiter");
    }

    @Test
    void unnestOfArrayColumnStillRefuses() {
        assertThatThrownBy(() -> toMysql("SELECT * FROM unnest(a.arr) AS u"))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("table function");
    }

    @Test
    void unmappableSrfStillRefusesHonestly() {
        assertThatThrownBy(() -> toMysql("SELECT * FROM generate_subscripts(a, 1) AS g"))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("table function");
    }
}
