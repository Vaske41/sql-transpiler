package rs.etf.sqltranslator.ast;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.SourcePosition;
import rs.etf.sqltranslator.parser.AstBuilderFacade;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InsertStatementTest {

    private static final SourcePosition POS = new SourcePosition(1, 0);

    private static QualifiedName table() {
        return new QualifiedName(List.of(new Identifier("t", false, POS)), POS);
    }

    private static Query query() {
        return ((SelectStatement) AstBuilderFacade
                .buildScript("SELECT 1", Dialect.MYSQL).statements().get(0)).query();
    }

    @Test
    void valuesAndQuerySourcesAreMutuallyExclusive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new InsertStatement(
                table(), List.of(),
                List.of(List.of(new NumericLiteral("1", false, POS))),
                Optional.of(query()), POS));
    }

    @Test
    void oneSourceFormIsRequired() {
        assertThatIllegalArgumentException().isThrownBy(() -> new InsertStatement(
                table(), List.of(), List.of(), Optional.empty(), POS));
    }
}
