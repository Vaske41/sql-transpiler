package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CteBuildTest {

    @Test
    void recursiveCteIsRefused() throws Exception {
        String sql = Files.readString(Path.of(
                "src/test/resources/cases/unsupported/recursive-cte/input.postgresql.sql"));
        assertThatThrownBy(() -> AstBuilderFacade.buildScript(sql, Dialect.POSTGRESQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("recursive CTE");
    }

    @Test
    void recursiveCteSelfReferenceIsRefused() throws Exception {
        String sql = Files.readString(Path.of(
                "src/test/resources/cases/unsupported/recursive-cte-self-ref/input.tsql.sql"));
        assertThatThrownBy(() -> AstBuilderFacade.buildScript(sql, Dialect.TSQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("recursive CTE");
    }
}
