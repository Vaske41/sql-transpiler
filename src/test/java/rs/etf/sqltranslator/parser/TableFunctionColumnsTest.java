package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;

import static org.assertj.core.api.Assertions.assertThat;

class TableFunctionColumnsTest {

    @Test
    void jsonbToRecordColumnDefinitionListParses() {
        String out = Translator.translate(
                "SELECT rec.circuitid FROM circuit_json, "
                        + "jsonb_to_record(circuit_id_name::jsonb) AS rec(circuitid int, name text)",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(out).containsIgnoringCase("rec(circuitid").containsIgnoringCase("name");
    }
}
