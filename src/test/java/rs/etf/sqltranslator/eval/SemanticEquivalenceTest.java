package rs.etf.sqltranslator.eval;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.Translator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Ordered MySQL↔PostgreSQL semantic equivalence over dedicated {@code cases/semantic/**}
 * scripts only. Tagged {@code integration}; excluded from default Surefire.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticEquivalenceTest {

    private static final Path SEMANTIC_ROOT =
            Path.of("src", "test", "resources", "cases", "semantic");

    private MySQLContainer<?> mysql;
    private PostgreSQLContainer<?> postgres;

    @TestFactory
    Stream<DynamicTest> mysqlPostgresOrderedEquivalence() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker required for semantic equivalence");

        ensureContainers();

        List<DynamicTest> tests = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(SEMANTIC_ROOT)) {
            List<Path> caseDirs = dirs.filter(Files::isDirectory).sorted().toList();
            for (Path caseDir : caseDirs) {
                String id = caseDir.getFileName().toString();
                Path mysqlInput = caseDir.resolve("input.mysql.sql");
                Path pgInput = caseDir.resolve("input.postgresql.sql");
                if (Files.exists(mysqlInput)) {
                    tests.add(dynamicCase(id, mysqlInput, Dialect.MYSQL, Dialect.POSTGRESQL));
                }
                if (Files.exists(pgInput)) {
                    tests.add(dynamicCase(id, pgInput, Dialect.POSTGRESQL, Dialect.MYSQL));
                }
            }
        }
        assertThat(tests)
                .as("expected MYSQL↔PG directions for semantic corpus")
                .isNotEmpty();
        return tests.stream();
    }

    private DynamicTest dynamicCase(String id, Path input, Dialect source, Dialect target) {
        String display = id + " " + tag(source) + "->" + tag(target);
        return DynamicTest.dynamicTest(display, () -> runEquivalence(input, source, target));
    }

    private void runEquivalence(Path input, Dialect source, Dialect target) throws Exception {
        String sourceScript = normalize(Files.readString(input, StandardCharsets.UTF_8));
        Scripts.SplitScript sourceSplit = Scripts.splitSetupAndFinalSelect(sourceScript);

        List<List<String>> expected;
        try (EngineSession sourceSession = openSession(source)) {
            sourceSession.executeSetup(join(sourceSplit.setupStatements()));
            expected = sourceSession.query(sourceSplit.finalSelect());
        }

        String translated = Translator.translate(sourceScript, source, target).sql();
        Scripts.SplitScript targetSplit = Scripts.splitSetupAndFinalSelect(translated);

        List<List<String>> actual;
        try (EngineSession targetSession = openSession(target)) {
            targetSession.executeSetup(join(targetSplit.setupStatements()));
            actual = targetSession.query(targetSplit.finalSelect());
        }

        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private void ensureContainers() {
        if (mysql == null) {
            mysql = MySqlTestcontainerSupport.newContainer();
            mysql.start();
        }
        if (postgres == null) {
            postgres = PostgreSqlTestcontainerSupport.newContainer();
            postgres.start();
        }
    }

    private EngineSession openSession(Dialect dialect) throws Exception {
        return switch (dialect) {
            case MYSQL -> MySqlTestcontainerSupport.openSession(mysql);
            case POSTGRESQL -> PostgreSqlTestcontainerSupport.openSession(postgres);
            case TSQL -> throw new IllegalArgumentException("TSQL not in day-one semantic suite");
        };
    }

    private static String join(List<String> statements) {
        return String.join(";\n", statements);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    private static String tag(Dialect dialect) {
        return dialect.name().toLowerCase(Locale.ROOT);
    }
}
