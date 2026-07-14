package rs.etf.sqltranslator.eval;

import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Shared SQL Server Testcontainers helper (pinned CU image; EULA via {@code acceptLicense()}).
 */
final class SqlServerEngineSupport {

    static final DockerImageName IMAGE =
            DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04");

    private SqlServerEngineSupport() {
    }

    static MSSQLServerContainer<?> newContainer() {
        return new MSSQLServerContainer<>(IMAGE).acceptLicense();
    }

    static EngineSession openSession(MSSQLServerContainer<?> container) throws SQLException {
        Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(),
                container.getUsername(),
                container.getPassword());
        return new JdbcEngineSession(connection);
    }
}
