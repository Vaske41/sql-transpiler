package rs.etf.sqltranslator.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptsTest {

    @Test
    void finalSelectWithoutOrderByIsRejected() {
        assertThatThrownBy(() -> Scripts.splitSetupAndFinalSelect(
                "CREATE TABLE t (id INT);\nINSERT INTO t VALUES (1);\nSELECT id FROM t;"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORDER BY");
    }

    @Test
    void finalSelectWithOrderByIsAccepted() {
        Scripts.SplitScript split = Scripts.splitSetupAndFinalSelect(
                "CREATE TABLE t (id INT);\nINSERT INTO t VALUES (1);\nSELECT id FROM t ORDER BY id;");
        assertThat(split.setupStatements()).hasSize(2);
        assertThat(split.finalSelect()).containsIgnoringCase("ORDER BY");
    }
}
