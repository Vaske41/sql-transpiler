package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AbstractAstVisitor;
import rs.etf.sqltranslator.ast.NumericLiteral;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.StringLiteral;
import rs.etf.sqltranslator.ast.ValuesTable;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

/** FROM (VALUES …) AS alias(cols) table primary. */
class ValuesTableTest {

    @Test
    void postgresMultiRowValuesTableWithColumnAliases() {
        ValuesTable table = firstValuesTable("""
                SELECT v.customerid, v.segment
                FROM (VALUES (3, 'SME'), (1, 'KAM')) AS v(customerid, segment);
                """, Dialect.POSTGRESQL);
        assertThat(table.alias().value()).isEqualTo("v");
        assertThat(table.columns()).extracting(c -> c.value())
                .containsExactly("customerid", "segment");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).values()).hasSize(2);
        assertThat(table.rows().get(0).values().get(0)).isInstanceOf(NumericLiteral.class);
        assertThat(table.rows().get(0).values().get(1)).isInstanceOf(StringLiteral.class);
        assertThat(((NumericLiteral) table.rows().get(1).values().get(0)).text()).isEqualTo("1");
    }

    @Test
    void mysqlValuesTableWithoutAsKeyword() {
        ValuesTable table = firstValuesTable("""
                SELECT r.x FROM (VALUES (1), (2)) r(x);
                """, Dialect.MYSQL);
        assertThat(table.alias().value()).isEqualTo("r");
        assertThat(table.columns()).extracting(c -> c.value()).containsExactly("x");
        assertThat(table.rows()).hasSize(2);
    }

    @Test
    void tsqlValuesTableOptionalColumnList() {
        ValuesTable table = firstValuesTable("""
                SELECT * FROM (VALUES (1, 'a'), (2, 'b')) AS t;
                """, Dialect.TSQL);
        assertThat(table.alias().value()).isEqualTo("t");
        assertThat(table.columns()).isEmpty();
        assertThat(table.rows()).hasSize(2);
    }

    private static ValuesTable firstValuesTable(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        ValuesTable[] found = new ValuesTable[1];
        script.accept(new AbstractAstVisitor<Void>() {
            @Override
            public Void visitValuesTable(ValuesTable node) {
                if (found[0] == null) {
                    found[0] = node;
                }
                return super.visitValuesTable(node);
            }
        });
        assertThat(found[0]).isNotNull();
        return found[0];
    }
}
