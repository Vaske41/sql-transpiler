package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.parser.CaseFiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Golden-file harness (ROADMAP Phase 7a, pulled forward per the amended Phase 5).
 * Expected files are snapshot-bootstrapped from tool output with
 * {@code -DupdateGolden=true}, then hand-reviewed — the honest methodology the
 * thesis documents; Testcontainers execution (Phase 7) is the independent check.
 */
class GoldenFileTest {

    private static final boolean UPDATE = Boolean.getBoolean("updateGolden");
    private static final Path CASES = Path.of("src", "test", "resources", "cases");

    private static final Set<String> EXPECTED_REFUSALS =
            CodegenTestSupport.EXPECTED_REFUSALS;    // single source of truth

    @TestFactory
    Stream<DynamicTest> goldenTranslations() {
        CaseFiles corpus = CaseFiles.under("/cases",
                p -> p.getFileName().toString().startsWith("input.")
                        && !p.toString().replace('\\', '/').contains("/unsupported/"));
        return corpus.files().stream().flatMap(file -> {
            String display = corpus.displayName(file);
            Dialect source = dialectOf(file.getFileName().toString());
            return Stream.of(Dialect.values())
                    .filter(target -> target != source)
                    .filter(target -> !EXPECTED_REFUSALS.contains(display + "|" + target))
                    .map(target -> DynamicTest.dynamicTest(display + " -> " + target,
                            () -> check(display, source, target)));
        });
    }

    private void check(String display, Dialect source, Dialect target) throws Exception {
        Path input = CASES.resolve(display.replace('/', java.io.File.separatorChar));
        Path golden = input.getParent().resolve("expected."
                + tag(source) + "." + tag(target) + ".sql");
        String actual = Translator.translate(normalize(Files.readString(input)),
                source, target).sql();

        if (UPDATE) {
            Files.writeString(golden, actual, StandardCharsets.UTF_8);
            return;
        }
        if (!Files.exists(golden)) {
            fail("Missing golden " + golden + " — bootstrap with"
                    + " ./mvnw -DupdateGolden=true -Dtest=GoldenFileTest test,"
                    + " then review the generated file");
        }
        assertThat(actual).isEqualTo(normalize(Files.readString(golden)));
    }

    /** Stale goldens must fail the build, not rot: every checked-in expected.* file
     *  has to map to a live (input, direction) pair. */
    @Test
    void everyGoldenBelongsToALiveDirection() throws Exception {
        CaseFiles corpus = CaseFiles.under("/cases",
                p -> p.getFileName().toString().startsWith("input.")
                        && !p.toString().replace('\\', '/').contains("/unsupported/"));
        Set<String> live = new HashSet<>();
        for (Path file : corpus.files()) {
            String display = corpus.displayName(file);
            Dialect source = dialectOf(file.getFileName().toString());
            for (Dialect target : Dialect.values()) {
                if (target != source
                        && !EXPECTED_REFUSALS.contains(display + "|" + target)) {
                    live.add(display.substring(0, display.lastIndexOf('/') + 1)
                            + "expected." + tag(source) + "." + tag(target) + ".sql");
                }
            }
        }
        try (Stream<Path> files = Files.walk(CASES)) {
            List<String> orphans = files
                    .filter(p -> p.getFileName().toString().startsWith("expected."))
                    .map(p -> CASES.relativize(p).toString().replace('\\', '/'))
                    .filter(golden -> !live.contains(golden))
                    .toList();
            assertThat(orphans)
                    .as("stale goldens with no live (input, direction) — delete them")
                    .isEmpty();
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static String tag(Dialect dialect) {
        return dialect.name().toLowerCase(Locale.ROOT);   // tsql, mysql, postgresql
    }

    private static Dialect dialectOf(String fileName) {
        String tag = fileName.substring("input.".length(), fileName.lastIndexOf('.'));
        return switch (tag) {
            case "tsql" -> Dialect.TSQL;
            case "mysql" -> Dialect.MYSQL;
            case "postgresql" -> Dialect.POSTGRESQL;
            default -> throw new IllegalArgumentException(fileName);
        };
    }
}
