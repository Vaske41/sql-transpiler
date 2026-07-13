package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.core.Dialect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Offline LLM fixtures under
 * {@code evaluation/results/{system}/{caseKey}/{src}-to-{tgt}.sql} (+ {@code .meta.json}).
 */
final class FixtureStore {

    private final Path root;

    FixtureStore() {
        this(Path.of("evaluation", "results"));
    }

    FixtureStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    Path root() {
        return root;
    }

    Path sqlPath(SystemId system, String caseKey, Dialect source, Dialect target) {
        return root
                .resolve(systemDir(system))
                .resolve(caseKey)
                .resolve(fileStem(source, target) + ".sql");
    }

    Path metaPath(SystemId system, String caseKey, Dialect source, Dialect target) {
        return root
                .resolve(systemDir(system))
                .resolve(caseKey)
                .resolve(fileStem(source, target) + ".meta.json");
    }

    Optional<String> readSql(SystemId system, String caseKey, Dialect source, Dialect target)
            throws IOException {
        Path path = sqlPath(system, caseKey, source, target);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
    }

    Optional<String> readMeta(SystemId system, String caseKey, Dialect source, Dialect target)
            throws IOException {
        Path path = metaPath(system, caseKey, source, target);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
    }

    void write(
            SystemId system,
            String caseKey,
            Dialect source,
            Dialect target,
            String sql,
            String model,
            String promptVersion,
            String utc,
            long latencyMs)
            throws IOException {
        Path sqlFile = sqlPath(system, caseKey, source, target);
        Files.createDirectories(sqlFile.getParent());
        Files.writeString(sqlFile, sql == null ? "" : sql, StandardCharsets.UTF_8);
        String meta = """
                {
                  "model": %s,
                  "promptVersion": %s,
                  "utc": %s,
                  "latencyMs": %d
                }
                """.formatted(
                jsonString(model),
                jsonString(promptVersion),
                jsonString(utc),
                latencyMs);
        Files.writeString(metaPath(system, caseKey, source, target), meta, StandardCharsets.UTF_8);
    }

    /**
     * Derives {@code category/case} under {@code cases/}, otherwise the last path segment.
     */
    static String caseKeyFrom(Path casePath) {
        Objects.requireNonNull(casePath, "casePath");
        String normalized = casePath.toString().replace('\\', '/');
        int idx = normalized.indexOf("/cases/");
        if (idx >= 0) {
            String rel = normalized.substring(idx + "/cases/".length());
            while (rel.endsWith("/")) {
                rel = rel.substring(0, rel.length() - 1);
            }
            if (!rel.isEmpty()) {
                return rel;
            }
        }
        Path name = casePath.getFileName();
        return name == null ? casePath.toString() : name.toString();
    }

    static String systemDir(SystemId system) {
        return system.name().toLowerCase(Locale.ROOT);
    }

    private static String fileStem(Dialect source, Dialect target) {
        return PromptTemplate.cliName(source) + "-to-" + PromptTemplate.cliName(target);
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
