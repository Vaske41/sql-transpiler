package rs.etf.sqltranslator.parser;

import org.junit.jupiter.api.Test;
import rs.etf.sqltranslator.core.Dialect;
import rs.etf.sqltranslator.core.UnsupportedFeatureException;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowBuildTest {

    @Test
    void windowFrameIsRefused() throws Exception {
        String sql = Files.readString(Path.of(
                "src/test/resources/cases/unsupported/window-frame/input.postgresql.sql"));
        assertThatThrownBy(() -> AstBuilderFacade.buildScript(sql, Dialect.POSTGRESQL))
                .isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("window frame");
    }
}
