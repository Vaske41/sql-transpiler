package rs.etf.sqltranslator.codegen;

import rs.etf.sqltranslator.ast.Script;
import rs.etf.sqltranslator.core.Dialect;

/** Strategy: one printer per target dialect. A fresh printer per call (stateful writer). */
public final class SqlPrinters {

    private SqlPrinters() {
    }

    public static String print(Script script, Dialect target) {
        return switch (target) {
            case TSQL -> new TSqlPrinter().print(script);
            case MYSQL -> new MySqlPrinter().print(script);
            case POSTGRESQL -> new PostgreSqlPrinter().print(script);
        };
    }
}
