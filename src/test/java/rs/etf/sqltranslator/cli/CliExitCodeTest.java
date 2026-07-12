package rs.etf.sqltranslator.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CliExitCodeTest {

    @Test
    void exitCodesMatchHarnessContract() {
        assertThat(CliExitCode.SUCCESS).isEqualTo(0);
        assertThat(CliExitCode.PARSE_ERROR).isEqualTo(1);
        assertThat(CliExitCode.UNSUPPORTED).isEqualTo(2);
        assertThat(CliExitCode.USAGE).isEqualTo(3);
        assertThat(CliExitCode.STRICT).isEqualTo(4);
        assertThat(CliExitCode.INTERNAL).isEqualTo(5);
    }
}
