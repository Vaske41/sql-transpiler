package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureStoreTest {

    @TempDir
    Path temp;

    @Test
    void sqlAndMetaPathsMatchContract() {
        FixtureStore store = new FixtureStore(temp);
        Path sql = store.sqlPath(
                SystemId.GEMINI, "select-basic/select-literal", Dialect.MYSQL, Dialect.POSTGRESQL);
        Path meta = store.metaPath(
                SystemId.GEMINI, "select-basic/select-literal", Dialect.MYSQL, Dialect.POSTGRESQL);

        assertThat(sql).isEqualTo(temp.resolve(
                "gemini/select-basic/select-literal/mysql-to-postgresql.sql"));
        assertThat(meta).isEqualTo(temp.resolve(
                "gemini/select-basic/select-literal/mysql-to-postgresql.meta.json"));
    }

    @Test
    void writeAndReadRoundTrip() throws Exception {
        FixtureStore store = new FixtureStore(temp);
        store.write(
                SystemId.COMPOSER,
                "select-basic/select-literal",
                Dialect.MYSQL,
                Dialect.POSTGRESQL,
                "SELECT 1;\n",
                ComposerAdapter.MODEL,
                PromptTemplate.VERSION,
                "2026-07-14T00:00:00Z",
                42L);

        assertThat(store.readSql(
                        SystemId.COMPOSER, "select-basic/select-literal", Dialect.MYSQL, Dialect.POSTGRESQL))
                .contains("SELECT 1;\n");
        String meta = store.readMeta(
                        SystemId.COMPOSER, "select-basic/select-literal", Dialect.MYSQL, Dialect.POSTGRESQL)
                .orElseThrow();
        assertThat(meta).contains("\"model\": \"composer-2.5\"");
        assertThat(meta).contains("\"promptVersion\": \"v1\"");
        assertThat(meta).contains("\"latencyMs\": 42");
    }

    @Test
    void writeMergesExtrasIntoMetaJson() throws Exception {
        FixtureStore store = new FixtureStore(temp);
        store.write(
                SystemId.COMPOSER,
                "select-basic/select-literal",
                Dialect.MYSQL,
                Dialect.POSTGRESQL,
                "SELECT 1;\n",
                ComposerAdapter.MODEL,
                PromptTemplate.VERSION,
                "2026-07-14T00:00:00Z",
                42L,
                java.util.Map.of("cursorSdk", "0.1.9"));

        String meta = store.readMeta(
                        SystemId.COMPOSER, "select-basic/select-literal", Dialect.MYSQL, Dialect.POSTGRESQL)
                .orElseThrow();
        assertThat(meta).contains("\"model\": \"composer-2.5\"");
        assertThat(meta).contains("\"cursorSdk\": \"0.1.9\"");
        assertThat(meta).contains("\"latencyMs\": 42");
    }

    @Test
    void caseKeyFromCasesPath() {
        Path casePath = Path.of("src", "test", "resources", "cases", "select-basic", "select-literal");
        assertThat(FixtureStore.caseKeyFrom(casePath)).isEqualTo("select-basic/select-literal");
    }

    @Test
    void missingReadIsEmpty() throws Exception {
        FixtureStore store = new FixtureStore(temp);
        assertThat(store.readSql(
                        SystemId.GEMINI, "missing/case", Dialect.MYSQL, Dialect.POSTGRESQL))
                .isEmpty();
        assertThat(Files.exists(temp)).isTrue();
    }
}
