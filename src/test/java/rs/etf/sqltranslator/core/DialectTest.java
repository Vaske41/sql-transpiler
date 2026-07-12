package rs.etf.sqltranslator.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialectTest {

    @Test
    void definesExactlyTheThreeThesisDialects() {
        assertThat(Dialect.values())
                .containsExactly(Dialect.TSQL, Dialect.MYSQL, Dialect.POSTGRESQL);
    }

    @Test
    void fromCliNameParsesCanonicalTokensCaseInsensitive() {
        assertThat(Dialect.fromCliName("tsql")).isEqualTo(Dialect.TSQL);
        assertThat(Dialect.fromCliName("MySQL")).isEqualTo(Dialect.MYSQL);
        assertThat(Dialect.fromCliName("POSTGRESQL")).isEqualTo(Dialect.POSTGRESQL);
    }

    @Test
    void fromCliNameRejectsUnknownAndBlank() {
        assertThatThrownBy(() -> Dialect.fromCliName("postgres"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tsql");
        assertThatThrownBy(() -> Dialect.fromCliName(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
