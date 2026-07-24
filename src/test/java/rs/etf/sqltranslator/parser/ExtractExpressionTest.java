package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.ColumnRef;
import rs.etf.sqltranslator.ast.ExtractExpression;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code EXTRACT(field FROM source)} — field uppercased; source expression retained.
 */
class ExtractExpressionTest {

    @Test
    void pgYearExtractNormalizesFieldUppercase() {
        ExtractExpression extract = firstExtract(
                "SELECT EXTRACT(YEAR FROM d) FROM t;", Dialect.POSTGRESQL);
        assertThat(extract.field()).isEqualTo("YEAR");
        assertThat(extract.source()).isInstanceOf(ColumnRef.class);
        assertThat(new AstDumper().dump(extract)).contains("ExtractExpression");
    }

    @Test
    void mysqlEpochExtractBuilds() {
        ExtractExpression extract = firstExtract(
                "SELECT EXTRACT(EPOCH FROM ts) FROM t;", Dialect.MYSQL);
        assertThat(extract.field()).isEqualTo("EPOCH");
    }

    @Test
    void tsqlDowExtractBuilds() {
        ExtractExpression extract = firstExtract(
                "SELECT EXTRACT(DOW FROM d) FROM t;", Dialect.TSQL);
        assertThat(extract.field()).isEqualTo("DOW");
    }

    private static ExtractExpression firstExtract(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        SelectStatement select = (SelectStatement) script.statements().get(0);
        return (ExtractExpression) ((SelectExpr) select.query().first().items().get(0)).expr();
    }
}
