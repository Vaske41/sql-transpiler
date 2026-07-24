package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class FetchWithTiesTest {

    @Test
    void fetchFirstRowWithTiesTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM orders ORDER BY id FETCH FIRST ROW WITH TIES;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql();
        assertThat(sql.toUpperCase()).contains("FETCH").contains("WITH").contains("TIES");
    }

    @Test
    void fetchWithTiesTowardMysqlBecomesLimitWithTies() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM orders ORDER BY id FETCH FIRST 1 ROW WITH TIES;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql();
        assertThat(sql.toUpperCase()).contains("LIMIT");
        assertThat(sql.toUpperCase()).contains("WITH").contains("TIES");
    }

    @Test
    void fetchWithTiesTowardTsqlBecomesTopWithTies() {
        String sql = CodegenTestSupport.printTranslated(
                "SELECT * FROM orders ORDER BY id FETCH FIRST 1 ROW WITH TIES;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql.toUpperCase()).contains("TOP");
        assertThat(sql.toUpperCase()).contains("WITH").contains("TIES");
    }
}
