package rs.etf.sqltranslator.eval;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared MySQL Testcontainers helper for semantic equivalence (pinned {@code mysql:8.4}).
 */
final class MySqlTestcontainerSupport {

    static final DockerImageName IMAGE = DockerImageName.parse("mysql:8.4");

    private MySqlTestcontainerSupport() {
    }

    static MySQLContainer<?> newContainer() {
        return new MySQLContainer<>(IMAGE)
                .withDatabaseName("eval")
                .withUsername("test")
                .withPassword("test");
    }

    static EngineSession openSession(MySQLContainer<?> container) throws SQLException {
        Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword());
        return new JdbcEngineSession(connection);
    }
}
