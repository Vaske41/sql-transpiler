package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rs.etf.sqltranslator.core.Dialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParrotCorpusTest {

    @Test
    void listsInputsAndReadsTarget(@TempDir Path tmp) throws Exception {
        Path c1 = tmp.resolve("00000-smoke-mysql-to-postgresql");
        Files.createDirectories(c1);
        Files.writeString(c1.resolve("input.mysql.sql"), "SELECT 1");
        Files.writeString(c1.resolve("target.txt"), "postgresql\n");
        List<Path> inputs = ParrotCorpus.listInputs(tmp);
        assertEquals(1, inputs.size());
        assertEquals(Dialect.POSTGRESQL, ParrotCorpus.readTarget(c1));
        assertEquals(
                "00000-smoke-mysql-to-postgresql",
                FixtureStore.caseKeyFrom(c1));
    }
}
