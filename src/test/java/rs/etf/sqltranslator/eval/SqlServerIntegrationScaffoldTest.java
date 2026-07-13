package rs.etf.sqltranslator.eval;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MSSQLServerContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in SQL Server smoke ({@code SELECT 1}). Tagged {@code sqlserver-integration};
 * excluded from default Surefire. Run with {@code -Psqlserver-integration}. Not in CI day one.
 */
@Tag("sqlserver-integration")
class SqlServerIntegrationScaffoldTest {

    @Test
    void selectOneSmoke() throws Exception {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker required for sqlserver-integration scaffold");

        try (MSSQLServerContainer<?> container = SqlServerEngineSupport.newContainer()) {
            container.start();
            try (EngineSession session = SqlServerEngineSupport.openSession(container)) {
                List<List<String>> rows = session.query("SELECT 1 AS n");
                assertThat(rows).containsExactly(List.of("1"));
            }
        }
    }
}
