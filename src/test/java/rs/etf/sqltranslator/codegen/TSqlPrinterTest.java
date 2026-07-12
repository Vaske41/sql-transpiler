package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TSqlPrinterTest {

    private String print(String tsqlSql) {
        Script script = AstBuilderFacade.buildScript(tsqlSql, Dialect.TSQL);
        return new TSqlPrinter().print(script);
    }

    @Test
    void bracketIdentifiersEscapeByDoubling() {
        assertThat(print("SELECT [we]]ird] FROM t;"))
                .isEqualTo("SELECT [we]]ird] FROM t;\n");
    }

    @Test
    void nationalStringsKeepTheirPrefix() {
        assertThat(print("SELECT N'O''Brien', 'plain';"))
                .isEqualTo("SELECT N'O''Brien', 'plain';\n");
    }

    @Test
    void topRendersParenthesizedAfterSelect() {
        assertThat(print("SELECT TOP 3 name FROM users ORDER BY id;"))
                .isEqualTo("SELECT TOP (3) name FROM users ORDER BY id;\n");
    }

    @Test
    void offsetFetchRendersInsideOrderBy() {
        assertThat(print("SELECT a FROM t ORDER BY a"
                + " OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY;"))
                .isEqualTo("SELECT a FROM t ORDER BY a"
                        + " OFFSET 5 ROWS FETCH NEXT 10 ROWS ONLY;\n");
    }

    @Test
    void limitOverUnionWithOrderByBecomesOffsetFetch() {
        // Rule-engine-shaped AST via a PG parse: UNION + shared ORDER BY + LIMIT.
        Script pg = AstBuilderFacade.buildScript(
                "SELECT id FROM t1 UNION ALL SELECT id FROM t2 ORDER BY id LIMIT 5;",
                Dialect.POSTGRESQL);
        assertThat(new TSqlPrinter().print(pg))
                .isEqualTo("SELECT id FROM t1 UNION ALL SELECT id FROM t2"
                        + " ORDER BY id OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY;\n");
    }

    @Test
    void typeTableAndIdentity() {
        assertThat(print("CREATE TABLE t (id INT IDENTITY(1,1) PRIMARY KEY,"
                + " n NVARCHAR(MAX), b BIT, ts DATETIME2);"))
                .isEqualTo("CREATE TABLE t (id INT IDENTITY(1,1) PRIMARY KEY,"
                        + " n NVARCHAR(MAX), b BIT, ts DATETIME2);\n");
    }

    @Test
    void alterAddOmitsColumnKeyword() {
        assertThat(print("ALTER TABLE t ADD c INT;"))
                .isEqualTo("ALTER TABLE t ADD c INT;\n");
    }

    @Test
    void concatOperatorRendersAsPlus() {
        Script pg = AstBuilderFacade.buildScript("SELECT a || b;", Dialect.POSTGRESQL);
        assertThat(new TSqlPrinter().print(pg)).isEqualTo("SELECT a + b;\n");
    }

    @Test
    void concatKeepsSemanticsParensAgainstArithmetic() {
        // CONCAT and ADD are the same '+' token in T-SQL: "a + b + 1" would evaluate
        // as (a + b) + 1 — different semantics under T-SQL coercion. Parens must survive.
        Script pg = AstBuilderFacade.buildScript("SELECT a || (b + 1);", Dialect.POSTGRESQL);
        assertThat(new TSqlPrinter().print(pg)).isEqualTo("SELECT a + (b + 1);\n");
    }

    @Test
    void distinctPrecedesTop() {
        assertThat(print("SELECT DISTINCT TOP 3 name FROM users ORDER BY name;"))
                .isEqualTo("SELECT DISTINCT TOP (3) name FROM users ORDER BY name;\n");
    }

    @Test
    void booleanLiteralIsAContractViolation() {
        Script pg = AstBuilderFacade.buildScript("SELECT TRUE;", Dialect.POSTGRESQL);
        assertThatThrownBy(() -> new TSqlPrinter().print(pg))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void notOverComparisonSurvivesPostRulesBooleanRewrite() {
        // Showcase exhibit shape: NOT deleted becomes NOT deleted <> 0 after rules.
        assertThat(CodegenTestSupport.printTranslated(
                "SELECT id FROM flags WHERE NOT deleted;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .isEqualTo("SELECT id FROM flags WHERE NOT deleted <> 0;\n");
    }
}
