package rs.etf.sqltranslator.transform;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.codegen.CodegenTestSupport;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AddTableConstraintTest {

    @Test
    void addForeignKeyParsesOnMysql() {
        assertThatCode(() -> AstBuilderFacade.buildScript(
                "ALTER TABLE member_privileges ADD FOREIGN KEY (member_id) "
                        + "REFERENCES member (member_id);",
                Dialect.MYSQL))
                .doesNotThrowAnyException();
    }

    @Test
    void addForeignKeyTowardPostgresql() {
        String sql = CodegenTestSupport.printTranslated(
                "ALTER TABLE member_privileges ADD FOREIGN KEY (member_id) "
                        + "REFERENCES member (member_id);",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql();
        assertThat(sql).containsIgnoringCase("ALTER TABLE");
        assertThat(sql).containsIgnoringCase("ADD");
        assertThat(sql).containsIgnoringCase("FOREIGN KEY");
        assertThat(sql).containsIgnoringCase("REFERENCES");
    }

    @Test
    void addNamedUniqueConstraintTowardTsql() {
        String sql = CodegenTestSupport.printTranslated(
                "ALTER TABLE t ADD CONSTRAINT uq UNIQUE (a, b);",
                Dialect.POSTGRESQL, Dialect.TSQL).sql();
        assertThat(sql).containsIgnoringCase("ADD");
        assertThat(sql).containsIgnoringCase("CONSTRAINT");
        assertThat(sql).containsIgnoringCase("UNIQUE");
    }
}
