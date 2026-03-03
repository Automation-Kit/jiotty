package net.yudichev.jiotty.connector.sonyprojector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SonyProjectorPowerStateTest {
    @Test
    void returnsProtocolValue() {
        assertThat(SonyProjectorPowerState.ON.protocolValue()).isEqualTo("on");
    }

    @Test
    void resolvesKnownProtocolValues() {
        assertThat(SonyProjectorPowerState.fromProtocolValue("standby")).isEqualTo(SonyProjectorPowerState.STANDBY);
    }

    @Test
    void returnsUnknownForUnexpectedValues() {
        assertThat(SonyProjectorPowerState.fromProtocolValue("ON")).isEqualTo(SonyProjectorPowerState.UNKNOWN);
    }
}
