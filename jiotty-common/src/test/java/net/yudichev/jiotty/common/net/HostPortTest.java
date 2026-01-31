package net.yudichev.jiotty.common.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostPortTest {

    @Test
    void parseValidHostPort() {
        HostPort hostPort = HostPort.parse("example.com:443");
        assertEquals("example.com", hostPort.host());
        assertEquals(443, hostPort.port());
    }

    @Test
    void parseValidIpAddress() {
        HostPort hostPort = HostPort.parse("127.0.0.1:8080");
        assertEquals("127.0.0.1", hostPort.host());
        assertEquals(8080, hostPort.port());
    }

    @Test
    void parseRejectsMissingPort() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                          () -> HostPort.parse("example.com"));
        assertEquals("Invalid host:port format: example.com", exception.getMessage());
    }

    @Test
    void parseRejectsNonNumericPort() {
        assertThrows(NumberFormatException.class, () -> HostPort.parse("example.com:abc"));
    }

    @Test
    void parseRejectsExtraColon() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                                                          () -> HostPort.parse("example.com:443:extra"));
        assertEquals("Invalid host:port format: example.com:443:extra", exception.getMessage());
    }
}
