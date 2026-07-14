package rs.etf.sqltranslator.evaluation;

/** Primary offline scoring outcomes for a single translation attempt. */
enum OutcomeKind {
    SUCCESS,
    REFUSED_OK,
    REFUSED,
    WRONG_INVENTION,
    PARSE,
    INTERNAL,
    NO_FIXTURE,
    ERROR
}
