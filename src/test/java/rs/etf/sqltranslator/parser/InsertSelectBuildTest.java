package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.ast.AstDumper;
import rs.etf.sqltranslator.ast.InsertStatement;
import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

import static org.assertj.core.api.Assertions.assertThat;

class InsertSelectBuildTest {

    @Test
    void insertSelectBuildsQuerySourceNotRows() {
        Script script = AstBuilderFacade.buildScript(
                "INSERT INTO archive (id) SELECT id FROM users WHERE id > 100",
                Dialect.MYSQL);
        InsertStatement insert = (InsertStatement) script.statements().get(0);
        assertThat(insert.rows()).isEmpty();
        assertThat(insert.query()).isPresent();
        assertThat(insert.columns()).hasSize(1);
    }

    @Test
    void insertValuesStillBuildsRows() {
        Script script = AstBuilderFacade.buildScript(
                "INSERT INTO archive (id) VALUES (1)", Dialect.MYSQL);
        InsertStatement insert = (InsertStatement) script.statements().get(0);
        assertThat(insert.rows()).hasSize(1);
        assertThat(insert.query()).isEmpty();
    }

    @Test
    void allThreeDialectsBuildTheSameInsertSelectAst() {
        String sql = "INSERT INTO archive (id) SELECT id FROM users WHERE id > 100";
        AstDumper dumper = new AstDumper();
        String tsql = dumper.dump(AstBuilderFacade.buildScript(sql, Dialect.TSQL));
        String mysql = dumper.dump(AstBuilderFacade.buildScript(sql, Dialect.MYSQL));
        String pg = dumper.dump(AstBuilderFacade.buildScript(sql, Dialect.POSTGRESQL));
        assertThat(mysql).isEqualTo(tsql);
        assertThat(pg).isEqualTo(tsql);
        assertThat(tsql).contains("query:");
    }
}
