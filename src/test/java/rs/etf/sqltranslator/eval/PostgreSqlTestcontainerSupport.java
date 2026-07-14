package rs.etf.sqltranslator.eval;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared PostgreSQL Testcontainers helper for semantic equivalence (pinned {@code postgres:16-alpine}).
 */
final class PostgreSqlTestcontainerSupport {

    static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    private PostgreSqlTestcontainerSupport() {
    }

    static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>(IMAGE)
                .withDatabaseName("eval")
                .withUsername("test")
                .withPassword("test");
    }

    static EngineSession openSession(PostgreSQLContainer<?> container) throws SQLException {
        Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword());
        return new JdbcEngineSession(connection);
    }
}
