package rs.etf.sqltranslator.eval;

import java.sql.SQLException;
import java.util.List;

/**
 * Engine-backed session used by semantic equivalence tests.
 */
interface EngineSession extends AutoCloseable {

    /** Run setup DDL/DML (no final SELECT). */
    void executeSetup(String scriptWithoutFinalSelect) throws SQLException;

    /** Execute a SELECT and return normalized rows (columns by position). */
    List<List<String>> query(String select) throws SQLException;

    @Override
    void close() throws SQLException;
}
