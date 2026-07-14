package rs.etf.sqltranslator.evaluation;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Parses {@code evaluation/.env.local} for live fixture regen without mutating the JVM env.
 * Process {@code getenv} wins over file values when non-blank (I6).
 */
final class EnvFiles {

    static final Path LOCAL = Path.of("evaluation", ".env.local");

    private EnvFiles() {
    }

    static Map<String, String> parse(String content) {
        if (content == null || content.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String rawLine : content.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (!key.isEmpty()) {
                out.put(key, value);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** Reads {@link #LOCAL} if present; otherwise empty map. */
    static Map<String, String> readLocal() {
        if (!Files.isRegularFile(LOCAL)) {
            return Map.of();
        }
        try {
            return parse(Files.readString(LOCAL, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Non-blank {@code System.getenv(name)} wins; else non-blank {@code fileMap} value.
     */
    static Optional<String> resolveSecret(String name, Map<String, String> fileMap) {
        String env = System.getenv(name);
        if (env != null && !env.isBlank()) {
            return Optional.of(env);
        }
        if (fileMap == null) {
            return Optional.empty();
        }
        String fromFile = fileMap.get(name);
        if (fromFile != null && !fromFile.isBlank()) {
            return Optional.of(fromFile);
        }
        return Optional.empty();
    }
}
