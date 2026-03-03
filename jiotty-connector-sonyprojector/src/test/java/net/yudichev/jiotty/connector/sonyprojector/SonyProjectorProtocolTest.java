package net.yudichev.jiotty.connector.sonyprojector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SonyProjectorProtocolTest {
    @Test
    void computesAuthorisationUsingSha256Hex() {
        assertThat(SonyProjectorProtocol.computeAuthorisation("ABCDEF", "secret"))
                .isEqualTo("a39945210ba8e56a7101e2595f957c5425c1f632944c880cbe980bd7918e71b1");
    }

    @Test
    void parsesPowerStatusResponse() {
        assertThat(SonyProjectorProtocol.parsePowerStatusResponse("power_status \"on\""))
                .isEqualTo(SonyProjectorPowerState.ON);
        assertThat(SonyProjectorProtocol.parsePowerStatusResponse("\"standby\""))
                .isEqualTo(SonyProjectorPowerState.STANDBY);
        assertThat(SonyProjectorProtocol.parsePowerStatusResponse("standby"))
                .isEqualTo(SonyProjectorPowerState.STANDBY);
    }

    @Test
    void rejectsUnexpectedResponse() {
        assertThatThrownBy(() -> SonyProjectorProtocol.parsePowerStatusResponse("err_cmd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recognisesNoKeyAndOkResponses() {
        assertThat(SonyProjectorProtocol.isNoKeyResponse("NOKEY")).isTrue();
        assertThat(SonyProjectorProtocol.isOkResponse("ok")).isTrue();
        assertThat(SonyProjectorProtocol.isAuthOkResponse("OK")).isTrue();
    }
}
