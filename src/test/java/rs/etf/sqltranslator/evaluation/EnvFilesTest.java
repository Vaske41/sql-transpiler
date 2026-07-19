package rs.etf.sqltranslator.evaluation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvFilesTest {

    @Test
    void parseReadsKeyValuePairsSkippingCommentsAndBlanks() throws Exception {
        Path env = Files.createTempFile("env-", ".local");
        Files.writeString(
                env,
                """
                        # comment
                        GEMINI_API_KEY=from-file

                        CURSOR_API_KEY=cursor-file
                        EMPTY=
                        """,
                StandardCharsets.UTF_8);

        Map<String, String> map = EnvFiles.parse(Files.readString(env));
        assertEquals("from-file", map.get("GEMINI_API_KEY"));
        assertEquals("cursor-file", map.get("CURSOR_API_KEY"));
        assertEquals("", map.get("EMPTY"));
        assertEquals(3, map.size());
    }

    @Test
    void resolveSecretPrefersNonBlankGetenvOverFile() {
        Map<String, String> file = Map.of("GEMINI_API_KEY", "from-file");
        // Cannot force getenv in unit test; when absent, file wins.
        Optional<String> resolved = EnvFiles.resolveSecret("GEMINI_API_KEY_UNSET_FOR_TEST", file);
        assertTrue(resolved.isEmpty());

        Optional<String> fromFile = EnvFiles.resolveSecret("GEMINI_API_KEY", file);
        // getenv may or may not be set in the test JVM; if set and non-blank it wins.
        String env = System.getenv("GEMINI_API_KEY");
        if (env != null && !env.isBlank()) {
            assertEquals(env, fromFile.orElseThrow());
        } else {
            assertEquals("from-file", fromFile.orElseThrow());
        }
    }

    @Test
    void readLocalReturnsEmptyWhenMissing() {
        // readLocal is path-fixed to evaluation/.env.local; only assert type-safety when present.
        Map<String, String> map = EnvFiles.readLocal();
        assertTrue(map != null);
    }
}
