package rs.etf.sqltranslator.eval;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * JDBC-backed {@link EngineSession}. Setup statements run sequentially;
 * {@link #query(String)} uses {@code executeQuery} + {@link ResultSetNormalizer}.
 */
final class JdbcEngineSession implements EngineSession {

    private final Connection connection;

    JdbcEngineSession(Connection connection) {
        this.connection = connection;
    }

    @Override
    public void executeSetup(String scriptWithoutFinalSelect) throws SQLException {
        List<String> statements = Scripts.splitStatements(scriptWithoutFinalSelect);
        try (Statement stmt = connection.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
        }
    }

    @Override
    public List<List<String>> query(String select) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(select)) {
            return ResultSetNormalizer.normalize(rs);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
