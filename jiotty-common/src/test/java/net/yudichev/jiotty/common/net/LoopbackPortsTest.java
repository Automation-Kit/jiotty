package net.yudichev.jiotty.common.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;

import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.condition.OS.MAC;

class LoopbackPortsTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test
    void chosenPortIsFreeAtTheLoopbackAddress() throws Exception {
        int port = LoopbackPorts.findFreeLoopbackPort();

        try (var socket = new ServerSocket(port, 0, LOOPBACK)) {
            assertThat(socket.getLocalPort()).isEqualTo(port);
        }
    }

    /// The half that holds on every platform, and the one the helper relies on: a port already taken at the loopback address is refused rather than handed out.
    @Test
    void aPortHeldAtTheLoopbackAddressIsRefused() throws Exception {
        try (var occupant = new ServerSocket(0, 0, LOOPBACK)) {
            assertThatThrownBy(() -> {
                // Closed in the branch where the bind wrongly succeeds, so a failing assertion does not also leak the socket.
                try (var _ = new ServerSocket(occupant.getLocalPort(), 0, LOOPBACK)) {
                    throw new AssertionError("bound a port already held at the loopback address");
                }
            }).isInstanceOf(BindException.class);
        }
    }

    /// Why the helper exists, and a BSD trait: a wildcard bind is allowed over a loopback listener, so `new ServerSocket(0)` reports an occupied port as free.
    /// Linux refuses that bind, which is why this arm is macOS-only while the helper is used everywhere.
    @Test
    @EnabledOnOs(MAC)
    void aWildcardBindSucceedsOverALoopbackListener() throws Exception {
        try (var occupant = new ServerSocket(0, 0, LOOPBACK);
             var wildcard = new ServerSocket(occupant.getLocalPort())) {
            assertThat(wildcard.isBound()).isTrue();
        }
    }
}
