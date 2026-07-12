package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.TranslationOutput;
import rs.etf.sqltranslator.core.Translator;
import rs.etf.sqltranslator.parser.CaseFiles;
import rs.etf.sqltranslator.parser.ParserFacade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TranslationRoundTripCorpusTest {

    private static final Set<String> EXPECTED_REFUSALS =
            CodegenTestSupport.EXPECTED_REFUSALS;    // single source of truth

    @TestFactory
    Stream<DynamicTest> everyRenderedOutputReparsesAndRepeats() {
        CaseFiles corpus = CaseFiles.under("/cases", CodegenTestSupport::isCorpusInput);
        return corpus.files().stream().flatMap(file ->
                Stream.of(Dialect.values())
                        .filter(target -> !EXPECTED_REFUSALS.contains(
                                corpus.displayName(file) + "|" + target))
                        .map(target -> DynamicTest.dynamicTest(
                                corpus.displayName(file) + " -> " + target,
                                () -> check(file, target))));
    }

    private void check(Path file, Dialect target) throws Exception {
        String sql = Files.readString(file);
        Dialect source = dialectOf(file.getFileName().toString());
        TranslationOutput first = Translator.translate(sql, source, target);
        TranslationOutput second = Translator.translate(sql, source, target);

        assertThat(second.sql()).isEqualTo(first.sql());           // determinism
        assertThatCode(() -> ParserFacade.parseScript(first.sql(), target))
                .doesNotThrowAnyException();                        // self-consistency
    }

    private static Dialect dialectOf(String fileName) {
        String tag = fileName.substring("input.".length(), fileName.lastIndexOf('.'));
        return switch (tag) {
            case "tsql" -> Dialect.TSQL;
            case "mysql" -> Dialect.MYSQL;
            case "postgresql" -> Dialect.POSTGRESQL;
            default -> throw new IllegalArgumentException(fileName);
        };
    }
}
