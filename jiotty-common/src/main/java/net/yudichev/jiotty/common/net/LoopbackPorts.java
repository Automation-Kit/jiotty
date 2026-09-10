package net.yudichev.jiotty.common.net;

import java.net.InetAddress;
import java.net.ServerSocket;

import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

/// Picks a port for a server that will listen on the loopback address.
///
/// The obvious `new ServerSocket(0)` is wrong for that, because it binds the wildcard address: macOS allows a wildcard bind over a listener already holding
/// that port on `127.0.0.1`, so the port comes back reported as free while another process owns the address the client will actually connect to. Binding the
/// loopback address is refused in that case on every platform, so the port is free where it has to be.
public final class LoopbackPorts {
    private LoopbackPorts() {
    }

    /// A port nothing is listening on at the loopback address. The caller races anything else picking ports on the machine, so bind it promptly.
    public static int findFreeLoopbackPort() {
        return getAsUnchecked(() -> {
            try (var socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            }
        });
    }
}
