package rs.etf.sqltranslator.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One exact-output exhibit per translation direction — the thesis's Table 1. */
class TranslatorShowcaseTest {

    @Test
    void tsqlToPostgres_topBecomesLimitAndNullOrderIsPreserved() {
        TranslationOutput out = Translator.translate(
                "SELECT TOP 3 name FROM users ORDER BY id;",
                Dialect.TSQL, Dialect.POSTGRESQL);
        assertThat(out.sql()).isEqualTo(
                "SELECT name FROM users ORDER BY id NULLS FIRST LIMIT 3;\n");
    }

    @Test
    void postgresToMySql_concatOperatorLowersToConcatCall() {
        TranslationOutput out = Translator.translate(
                "SELECT 'a' || name FROM users ORDER BY id LIMIT 5 OFFSET 2;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(out.sql()).isEqualTo(
                "SELECT CONCAT('a', name) FROM users ORDER BY id LIMIT 5 OFFSET 2;\n");
    }

    @Test
    void mysqlToTsql_functionsFoldAndLimitBecomesTop() {
        TranslationOutput out = Translator.translate(
                "SELECT NOW(), IFNULL(a, 0) FROM t LIMIT 7;",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(out.sql()).isEqualTo(
                "SELECT TOP (7) GETDATE(), COALESCE(a, 0) FROM t;\n");
    }

    @Test
    void mysqlToTsql_ddlAutoIncrementBecomesIdentity() {
        TranslationOutput out = Translator.translate(
                "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY,"
                        + " name VARCHAR(50) NOT NULL);",
                Dialect.MYSQL, Dialect.TSQL);
        assertThat(out.sql()).isEqualTo(
                "CREATE TABLE t (id INT IDENTITY(1,1) PRIMARY KEY,"
                        + " name VARCHAR(50) NOT NULL);\n");
    }

    @Test
    void tsqlToMySql_nationalStringPlusBecomesConcatCall() {
        TranslationOutput out = Translator.translate(
                "SELECT N'café' + name FROM users WHERE active = 1;",
                Dialect.TSQL, Dialect.MYSQL);
        assertThat(out.sql()).isEqualTo(
                "SELECT CONCAT('café', name) FROM users WHERE active = 1;\n");
    }

    @Test
    void postgresToTsql_barePredicatesGetExplicitTruthiness() {
        TranslationOutput out = Translator.translate(
                "SELECT id FROM flags WHERE active AND NOT deleted;",
                Dialect.POSTGRESQL, Dialect.TSQL);
        assertThat(out.sql()).isEqualTo(
                "SELECT id FROM flags WHERE active <> 0 AND NOT deleted <> 0;\n");
    }

    @Test
    void reportTravelsWithTheOutput() {
        TranslationOutput out = Translator.translate(
                "SELECT a FROM t ORDER BY a DESC NULLS LAST;",
                Dialect.POSTGRESQL, Dialect.MYSQL);
        assertThat(out.report().warnings())
                .anyMatch(w -> w.code().equals("NULLS_ORDERING_DROPPED"));
    }
}
