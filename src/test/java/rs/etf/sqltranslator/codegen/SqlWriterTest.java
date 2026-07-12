package rs.etf.sqltranslator.codegen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlWriterTest {

    @Test
    void tokensAreSeparatedBySingleSpaces() {
        assertThat(new SqlWriter().token("SELECT").token("1").result())
                .isEqualTo("SELECT 1");
    }

    @Test
    void noLeadingSpaceAtStart() {
        assertThat(new SqlWriter().token("SELECT").result()).isEqualTo("SELECT");
    }

    @Test
    void rawAppendsVerbatimAndSuppressesFollowingTokenSpaceAfterParen() {
        // f(a, b): token f, raw (, token a, raw ",", token b, raw )
        String sql = new SqlWriter().token("f").raw("(").token("a").raw(",")
                .token("b").raw(")").result();
        assertThat(sql).isEqualTo("f(a, b)");
    }

    @Test
    void parenthesizedGroupReadsNaturally() {
        // a - (b - c)
        String sql = new SqlWriter().token("a").token("-").token("(").token("b")
                .token("-").token("c").raw(")").result();
        assertThat(sql).isEqualTo("a - (b - c)");
    }
}
