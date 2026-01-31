package net.yudichev.jiotty.common.net;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostPortTest {

    @Test
    void parseValidHostPort() {
        HostPort hostPort = HostPort.parse("example.com:443");
        assertThat(hostPort.host()).isEqualTo("example.com");
        assertThat(hostPort.port()).isEqualTo(443);
    }

    @Test
    void parseValidIpAddress() {
        HostPort hostPort = HostPort.parse("127.0.0.1:8080");
        assertThat(hostPort.host()).isEqualTo("127.0.0.1");
        assertThat(hostPort.port()).isEqualTo(8080);
    }

    @Test
    void parseRejectsMissingPort() {
        assertThatThrownBy(() -> HostPort.parse("example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid host:port format: example.com");
    }

    @Test
    void parseRejectsNonNumericPort() {
        assertThatThrownBy(() -> HostPort.parse("example.com:abc"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void parseRejectsExtraColon() {
        assertThatThrownBy(() -> HostPort.parse("example.com:443:extra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid host:port format: example.com:443:extra");
    }
}
