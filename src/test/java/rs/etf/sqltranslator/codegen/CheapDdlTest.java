package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.TranslationOutput;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static rs.etf.sqltranslator.codegen.CodegenTestSupport.printTranslated;

/** Cheap DDL: DROP VIEW/INDEX/FUNCTION, ALTER COLUMN type, TRUNCATE. */
class CheapDdlTest {

    @Test
    void dropViewTranslatesNativelyAllDirections() {
        assertThat(printTranslated("DROP VIEW IF EXISTS findcount;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .isEqualTo("DROP VIEW IF EXISTS findcount;\n");
        assertThat(printTranslated("DROP VIEW IF EXISTS findcount;",
                Dialect.MYSQL, Dialect.TSQL).sql())
                .isEqualTo("DROP VIEW IF EXISTS findcount;\n");
        assertThat(printTranslated("DROP VIEW findcount;",
                Dialect.TSQL, Dialect.POSTGRESQL).sql())
                .isEqualTo("DROP VIEW findcount;\n");
    }

    @Test
    void dropIndexWithTableRendersPerDialect() {
        assertThat(printTranslated("DROP INDEX idx_x ON t;",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql())
                .isEqualTo("DROP INDEX idx_x;\n");
        assertThat(printTranslated("DROP INDEX idx_x ON t;",
                Dialect.MYSQL, Dialect.TSQL).sql())
                .isEqualTo("DROP INDEX idx_x ON t;\n");
        assertThat(printTranslated("DROP INDEX IF EXISTS idx_x;",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql())
                .isEqualTo("DROP INDEX IF EXISTS idx_x;\n");
    }

    @Test
    void dropIndexWithoutTableRefusedTowardMysqlAndTsql() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> printTranslated("DROP INDEX IF EXISTS idx_x;",
                        Dialect.POSTGRESQL, Dialect.MYSQL));
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> printTranslated("DROP INDEX IF EXISTS idx_x;",
                        Dialect.POSTGRESQL, Dialect.TSQL));
    }

    @Test
    void dropFunctionNoArgStripsCascadeTowardMysql() {
        TranslationOutput out = printTranslated(
                "DROP FUNCTION IF EXISTS update_school_inventory_after_supply CASCADE;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(out.sql())
                .isEqualTo("DROP FUNCTION IF EXISTS update_school_inventory_after_supply;\n");
        assertThat(out.report().warnings())
                .anyMatch(w -> w.code().equals("CASCADE_DROPPED"));
    }

    @Test
    void dropFunctionEmptyParensOk() {
        assertThat(printTranslated(
                "DROP FUNCTION IF EXISTS update_school_inventory_after_supply();",
                Dialect.POSTGRESQL, Dialect.POSTGRESQL).sql())
                .isEqualTo("DROP FUNCTION IF EXISTS update_school_inventory_after_supply();\n");
        assertThat(printTranslated(
                "DROP FUNCTION IF EXISTS update_school_inventory_after_supply();",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .isEqualTo("DROP FUNCTION IF EXISTS update_school_inventory_after_supply;\n");
    }

    @Test
    void dropFunctionWithArgTypesRefusedTowardMysql() {
        assertThatExceptionOfType(UnsupportedFeatureException.class)
                .isThrownBy(() -> printTranslated(
                        "DROP FUNCTION IF EXISTS foo(INTEGER);",
                        Dialect.POSTGRESQL, Dialect.MYSQL));
    }

    @Test
    void alterColumnTypeMapsSpellings() {
        assertThat(printTranslated(
                "ALTER TABLE account ALTER COLUMN date TYPE TIMESTAMP;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .isEqualTo("ALTER TABLE account MODIFY COLUMN date DATETIME;\n");
        assertThat(printTranslated(
                "ALTER TABLE account MODIFY COLUMN date DATETIME;",
                Dialect.MYSQL, Dialect.POSTGRESQL).sql())
                .isEqualTo("ALTER TABLE account ALTER COLUMN date TYPE TIMESTAMP;\n");
        assertThat(printTranslated(
                "ALTER TABLE account ALTER COLUMN date TYPE TIMESTAMP;",
                Dialect.POSTGRESQL, Dialect.TSQL).sql())
                .isEqualTo("ALTER TABLE account ALTER COLUMN date DATETIME2;\n");
    }

    @Test
    void alterColumnUsingDroppedTowardMysqlWithWarning() {
        TranslationOutput out = printTranslated(
                "ALTER TABLE account ALTER COLUMN date TYPE TIMESTAMP USING date::TIMESTAMP;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(out.sql())
                .isEqualTo("ALTER TABLE account MODIFY COLUMN date DATETIME;\n");
        assertThat(out.report().warnings())
                .anyMatch(w -> w.code().equals("USING_DROPPED"));
    }

    @Test
    void truncateTranslatesNatively() {
        assertThat(printTranslated("TRUNCATE TABLE t;",
                Dialect.POSTGRESQL, Dialect.MYSQL).sql())
                .isEqualTo("TRUNCATE TABLE t;\n");
        assertThat(printTranslated("TRUNCATE t;",
                Dialect.MYSQL, Dialect.TSQL).sql())
                .isEqualTo("TRUNCATE TABLE t;\n");
    }
}
