package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlPrinterTest {

    private String print(String mysqlSql) {
        Script script = AstBuilderFacade.buildScript(mysqlSql, Dialect.MYSQL);
        return new MySqlPrinter().print(script);
    }

    @Test
    void backtickIdentifiersEscapeByDoubling() {
        assertThat(print("SELECT `we``ird` FROM t;"))
                .isEqualTo("SELECT `we``ird` FROM t;\n");
    }

    @Test
    void stringsEscapeBackslashThenQuote() {
        // value a\b'c  →  'a\\b''c'
        assertThat(print("SELECT 'a\\\\b\\'c';"))
                .isEqualTo("SELECT 'a\\\\b''c';\n");
    }

    @Test
    void booleanLiteralAndBooleanTypeAreMySqlIdioms() {
        assertThat(print("SELECT TRUE;")).isEqualTo("SELECT TRUE;\n");
        assertThat(print("CREATE TABLE t (f BOOLEAN);"))
                .isEqualTo("CREATE TABLE t (f TINYINT(1));\n");
    }

    @Test
    void typeTableSpotChecks() {
        assertThat(print("CREATE TABLE t (a INT, b DATETIME, c DOUBLE, d TEXT, e BLOB);"))
                .isEqualTo("CREATE TABLE t (a INT, b DATETIME, c DOUBLE, d TEXT, e BLOB);\n");
    }

    @Test
    void autoIncrementRenders() {
        assertThat(print("CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY);"))
                .isEqualTo("CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY);\n");
    }

    @Test
    void limitShape() {
        assertThat(print("SELECT a FROM t ORDER BY a LIMIT 10 OFFSET 5;"))
                .isEqualTo("SELECT a FROM t ORDER BY a LIMIT 10 OFFSET 5;\n");
    }

    @Test
    void concatOperatorIsAContractViolation() {
        // PG-sourced AST carries BinaryOp(CONCAT); Phase 4 lowers it for MySQL —
        // reaching this printer with one is a bug, never silent output.
        Script pg = AstBuilderFacade.buildScript("SELECT a || b;", Dialect.POSTGRESQL);
        assertThatThrownBy(() -> new MySqlPrinter().print(pg))
                .isInstanceOf(IllegalStateException.class);
    }
}
