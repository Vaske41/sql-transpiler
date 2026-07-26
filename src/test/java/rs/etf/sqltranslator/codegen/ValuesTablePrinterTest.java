package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

/** VALUES-as-table renders natively in all three dialects. */
class ValuesTablePrinterTest {

    @Test
    void valuesTableRoundTripsTowardAllTargets() {
        String sql = """
                SELECT v.customerid, v.segment
                FROM (VALUES (3, 'SME'), (1, 'KAM')) AS v(customerid, segment);
                """;
        for (Dialect target : new Dialect[]{Dialect.POSTGRESQL, Dialect.MYSQL, Dialect.TSQL}) {
            String out = CodegenTestSupport.printTranslated(sql, Dialect.POSTGRESQL, target).sql();
            assertThat(out).containsIgnoringCase("VALUES");
            assertThat(out).contains("(3,");
            assertThat(out).contains("'SME'");
            assertThat(out).containsIgnoringCase("AS v");
            assertThat(out).containsIgnoringCase("customerid");
            assertThat(out).containsIgnoringCase("segment");
        }
    }
}
