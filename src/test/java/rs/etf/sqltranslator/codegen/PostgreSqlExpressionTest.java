package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static rs.etf.sqltranslator.codegen.CodegenTestSupport.expr;
import static rs.etf.sqltranslator.codegen.CodegenTestSupport.where;

class PostgreSqlExpressionTest {

    private String print(String selectSql) {
        return new PostgreSqlPrinter()
                .printExpression(expr(selectSql, Dialect.POSTGRESQL));
    }

    @Test
    void precedenceNeedsNoParensWhenStructureMatchesBinding() {
        assertThat(print("SELECT a + b * c;")).isEqualTo("a + b * c");
    }

    @Test
    void lowerPrecedenceChildRegainsItsParens() {
        assertThat(print("SELECT (a + b) * c;")).isEqualTo("(a + b) * c");
    }

    @Test
    void rightAssociativeGroupingOfLeftAssociativeOpIsParenthesized() {
        assertThat(print("SELECT a - (b - c);")).isEqualTo("a - (b - c)");
    }

    @Test
    void comparisonLevelIsNonAssociative() {
        // The grammars' predicate rule cannot chain comparisons — parens survive
        // on BOTH sides, or the output is unparseable.
        assertThat(print("SELECT (a = b) = c;")).isEqualTo("(a = b) = c");
        assertThat(print("SELECT a = (b = c);")).isEqualTo("a = (b = c)");
    }

    @Test
    void nestedSignsKeepParensInsteadOfFusingIntoAComment() {
        // Fused "--" opens a line comment in T-SQL/PG — never emit it.
        assertThat(print("SELECT -(-5);")).isEqualTo("-(-5)");
    }

    @Test
    void leftNestedSameOpNeedsNoParens() {
        assertThat(print("SELECT a - b - c;")).isEqualTo("a - b - c");
    }

    @Test
    void booleanLadderAndNot() {
        assertThat(print("SELECT NOT (a OR b);")).isEqualTo("NOT (a OR b)");
        assertThat(print("SELECT a OR b AND c;")).isEqualTo("a OR b AND c");
    }

    @Test
    void unarySignFusesWithOperand() {
        assertThat(print("SELECT -5;")).isEqualTo("-5");
        assertThat(print("SELECT -(a + b);")).isEqualTo("-(a + b)");
    }

    @Test
    void concatRendersAsPipes() {
        assertThat(print("SELECT a || b || c;")).isEqualTo("a || b || c");
    }

    @Test
    void stringEscapingDoublesQuotes() {
        assertThat(print("SELECT 'O''Brien';")).isEqualTo("'O''Brien'");
    }

    @Test
    void backslashIsLiteralInPostgresStrings() {
        assertThat(print("SELECT 'a\\b';")).isEqualTo("'a\\b'");
    }

    @Test
    void identifiersQuoteOnlyWhenFlaggedOrUnquotable() {
        assertThat(print("SELECT name;")).isEqualTo("name");
        assertThat(print("SELECT \"we\"\"ird\";")).isEqualTo("\"we\"\"ird\"");
    }

    @Test
    void predicatesRenderCanonically() {
        assertThat(printWhere("SELECT x FROM t WHERE a NOT BETWEEN 1 AND 5;"))
                .isEqualTo("a NOT BETWEEN 1 AND 5");
        assertThat(printWhere("SELECT x FROM t WHERE a IN (1, 2, 3);"))
                .isEqualTo("a IN (1, 2, 3)");
        assertThat(printWhere("SELECT x FROM t WHERE a IS NOT NULL;"))
                .isEqualTo("a IS NOT NULL");
        assertThat(printWhere("SELECT x FROM t WHERE a LIKE 'x%';"))
                .isEqualTo("a LIKE 'x%'");
    }

    private String printWhere(String sql) {
        return new PostgreSqlPrinter().printExpression(where(sql, Dialect.POSTGRESQL));
    }

    @Test
    void functionsCaseAndCast() {
        assertThat(print("SELECT COUNT(*);")).isEqualTo("COUNT(*)");
        assertThat(print("SELECT COUNT(DISTINCT x);")).isEqualTo("COUNT(DISTINCT x)");
        assertThat(print("SELECT COALESCE(a, 0);")).isEqualTo("COALESCE(a, 0)");
        assertThat(print("SELECT CAST(x AS VARCHAR(10));"))
                .isEqualTo("CAST(x AS VARCHAR(10))");
        assertThat(print("SELECT CASE WHEN a > 1 THEN 'x' ELSE 'y' END;"))
                .isEqualTo("CASE WHEN a > 1 THEN 'x' ELSE 'y' END");
    }
}
