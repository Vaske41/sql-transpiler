package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.IntervalLiteral;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.ast.SelectExpr;
import rs.etf.sqltranslator.ast.SelectStatement;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTERVAL literals — PG {@code INTERVAL '1 day'} and MySQL {@code INTERVAL 1 DAY} /
 * {@code INTERVAL '1' MINUTE}, normalized to {@code {value, unit}}.
 */
class IntervalLiteralTest {

    @Test
    void pgStringFormNormalizesValueAndUnit() {
        IntervalLiteral lit = firstInterval(
                "SELECT INTERVAL '1 day' FROM t;", Dialect.POSTGRESQL);
        assertThat(lit.raw()).isEqualTo("1");
        assertThat(lit.unit()).contains("day");
        assertThat(new AstDumper().dump(lit)).contains("IntervalLiteral");
    }

    @Test
    void pgPluralUnitSingularizes() {
        IntervalLiteral lit = firstInterval(
                "SELECT INTERVAL '16 years' FROM t;", Dialect.POSTGRESQL);
        assertThat(lit.raw()).isEqualTo("16");
        assertThat(lit.unit()).contains("year");
    }

    @Test
    void mysqlNumericFormNormalizes() {
        IntervalLiteral lit = firstInterval(
                "SELECT INTERVAL 1 DAY FROM t;", Dialect.MYSQL);
        assertThat(lit.raw()).isEqualTo("1");
        assertThat(lit.unit()).contains("day");
    }

    @Test
    void mysqlStringValueWithUnitNormalizes() {
        IntervalLiteral lit = firstInterval(
                "SELECT INTERVAL '1' MINUTE FROM t;", Dialect.MYSQL);
        assertThat(lit.raw()).isEqualTo("1");
        assertThat(lit.unit()).contains("minute");
    }

    @Test
    void compoundIntervalKeepsRawWithoutUnit() {
        IntervalLiteral lit = firstInterval(
                "SELECT INTERVAL '1 day 03:00' FROM t;", Dialect.POSTGRESQL);
        assertThat(lit.raw()).isEqualTo("1 day 03:00");
        assertThat(lit.unit()).isEmpty();
    }

    private static IntervalLiteral firstInterval(String sql, Dialect dialect) {
        Script script = AstBuilderFacade.buildScript(sql, dialect);
        SelectStatement select = (SelectStatement) script.statements().get(0);
        return (IntervalLiteral) ((SelectExpr) select.query().first().items().get(0)).expr();
    }
}
