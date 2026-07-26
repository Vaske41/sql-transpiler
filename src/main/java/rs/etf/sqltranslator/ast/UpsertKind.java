package rs.etf.sqltranslator.ast;

/** Kind of INSERT upsert clause. */
public enum UpsertKind {
    ON_CONFLICT_NOTHING,
    ON_CONFLICT_UPDATE,
    ON_DUPLICATE_KEY
}
