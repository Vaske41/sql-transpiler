package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;

class PostgreSqlStatementTest {

    private String print(String sql) {
        Script script = AstBuilderFacade.buildScript(sql, Dialect.POSTGRESQL);
        return new PostgreSqlPrinter().print(script);
    }

    @Test
    void selectWithJoinAliasWhereOrderLimit() {
        assertThat(print("select p.name, o.qty from products p"
                + " inner join orders o on p.id = o.product_id"
                + " where o.qty > 5 order by p.name desc limit 10 offset 2;"))
                .isEqualTo("SELECT p.name, o.qty FROM products AS p"
                        + " INNER JOIN orders AS o ON p.id = o.product_id"
                        + " WHERE o.qty > 5 ORDER BY p.name DESC LIMIT 10 OFFSET 2;\n");
    }

    @Test
    void unionAllWithSharedOrderBy() {
        assertThat(print("SELECT id FROM t1 UNION ALL SELECT id FROM t2 ORDER BY id;"))
                .isEqualTo("SELECT id FROM t1 UNION ALL SELECT id FROM t2 ORDER BY id;\n");
    }

    @Test
    void groupByHavingAndDistinct() {
        assertThat(print("SELECT DISTINCT dept, COUNT(*) FROM emp"
                + " GROUP BY dept HAVING COUNT(*) > 3;"))
                .isEqualTo("SELECT DISTINCT dept, COUNT(*) FROM emp"
                        + " GROUP BY dept HAVING COUNT(*) > 3;\n");
    }

    @Test
    void nullsOrderingAndBareOffsetSurvive() {
        assertThat(print("SELECT a FROM t ORDER BY a NULLS LAST OFFSET 4;"))
                .isEqualTo("SELECT a FROM t ORDER BY a NULLS LAST OFFSET 4;\n");
    }

    @Test
    void starAndQualifiedStar() {
        assertThat(print("SELECT *, t.* FROM t;")).isEqualTo("SELECT *, t.* FROM t;\n");
    }

    @Test
    void subqueriesInPredicates() {
        assertThat(print("SELECT a FROM t WHERE a IN (SELECT b FROM u)"
                + " AND EXISTS (SELECT 1 FROM v);"))
                .isEqualTo("SELECT a FROM t WHERE a IN (SELECT b FROM u)"
                        + " AND EXISTS (SELECT 1 FROM v);\n");
    }

    @Test
    void insertMultiRowUpdateDelete() {
        assertThat(print("INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y');"
                + "UPDATE t SET a = 3, b = 'z' WHERE a = 1;"
                + "DELETE FROM t WHERE b IS NULL;"))
                .isEqualTo("INSERT INTO t (a, b) VALUES (1, 'x'), (2, 'y');\n"
                        + "UPDATE t SET a = 3, b = 'z' WHERE a = 1;\n"
                        + "DELETE FROM t WHERE b IS NULL;\n");
    }

    @Test
    void insertWithoutColumnList() {
        assertThat(print("INSERT INTO t VALUES (1, 2);"))
                .isEqualTo("INSERT INTO t VALUES (1, 2);\n");
    }
}
