package rs.etf.sqltranslator.evaluation;

import rs.etf.sqltranslator.core.Dialect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Walks materialized PARROT-Diverse cases ({@code input.*.sql} + sibling {@code target.txt}).
 */
final class ParrotCorpus {

    private ParrotCorpus() {
    }

    static List<Path> listInputs(Path casesRoot) {
        Objects.requireNonNull(casesRoot, "casesRoot");
        try (Stream<Path> walk = Files.walk(casesRoot)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(ParrotCorpus::isInputSql)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Dialect readTarget(Path caseDir) throws IOException {
        Objects.requireNonNull(caseDir, "caseDir");
        String token = Files.readString(caseDir.resolve("target.txt"), StandardCharsets.UTF_8).trim();
        return Dialect.fromCliName(token);
    }

    /**
     * Relative case directory under {@code casesRoot} (CSV {@code case_id}).
     */
    static String displayName(Path casesRoot, Path inputFile) {
        Objects.requireNonNull(casesRoot, "casesRoot");
        Objects.requireNonNull(inputFile, "inputFile");
        Path caseDir = inputFile.getParent();
        if (caseDir == null) {
            throw new IllegalArgumentException("input has no parent: " + inputFile);
        }
        return casesRoot.relativize(caseDir).toString().replace('\\', '/');
    }

    private static boolean isInputSql(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("input.") && name.endsWith(".sql");
    }
}
