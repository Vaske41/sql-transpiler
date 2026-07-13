package rs.etf.sqltranslator.eval;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ResultSetNormalizerTest {

    @Test
    void doesNotTrimStrings() {
        assertThat(ResultSetNormalizer.normalizeValue("  x ")).isEqualTo("  x ");
    }

    @Test
    void normalizesNullAndDecimals() {
        assertThat(ResultSetNormalizer.normalizeValue(null)).isEqualTo("NULL");
        assertThat(ResultSetNormalizer.normalizeValue(new BigDecimal("1.00"))).isEqualTo("1");
        assertThat(ResultSetNormalizer.normalizeValue(Boolean.TRUE)).isEqualTo("1");
    }
}
