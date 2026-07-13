package rs.etf.sqltranslator.evaluation;

/** Pluggable baseline that turns a {@link TranslateRequest} into a scored outcome. */
interface TranslatorAdapter {
    SystemId systemId();

    TranslateOutcome translate(TranslateRequest request) throws Exception;
}
