package net.yudichev.jiotty.connector.mqtt;

import io.moquette.broker.Server;
import io.moquette.broker.config.IConfig;
import io.moquette.broker.config.MemoryConfig;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.ServerSocket;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

/// JUnit 5 extension that boots a real in-JVM Moquette MQTT broker for MQTT integration tests, mirroring the embedded-Postgres extension in the persistence
/// modules: a real server (here an MQTT broker, there Postgres) started once per test class, reachable over loopback on an ephemeral port, with no Docker
/// dependency. Use it to exercise [MqttImpl] against genuine MQTT semantics — retained-message redelivery, per-subscription delivery, reconnect
/// resubscription — that mock- or in-memory-based tests cannot reproduce.
///
/// The broker keeps retained state for its whole lifetime, so each test must use its own unique topics to stay isolated; the broker itself is not reset
/// between tests (except by an explicit [#restart]).
public final class EmbeddedMqttBrokerExtension implements BeforeAllCallback, AfterAllCallback {
    /// The running broker; `null` before it is started and after it is stopped.
    private @Nullable Server broker;
    private int port;

    @Override
    public void beforeAll(ExtensionContext context) {
        port = findFreePort();
        startBroker();
    }

    @Override
    // null after teardown so serverUri() fails fast if it is used post-shutdown
    @SuppressWarnings("AssignmentToNull")
    public void afterAll(ExtensionContext context) {
        if (broker != null) {
            broker.stopServer();
            broker = null;
        }
    }

    /// Stops and restarts the broker on the same port, forcing connected clients to lose their connection and auto-reconnect — used to exercise
    /// reconnect/resubscription behaviour. Retained state does not survive the restart.
    public void restart() {
        if (broker != null) {
            broker.stopServer();
        }
        startBroker();
    }

    public String serverUri() {
        checkState(broker != null, "Embedded MQTT broker is not started");
        return "tcp://127.0.0.1:" + port;
    }

    private void startBroker() {
        var properties = new Properties();
        properties.setProperty(IConfig.HOST_PROPERTY_NAME, "127.0.0.1");
        properties.setProperty(IConfig.PORT_PROPERTY_NAME, Integer.toString(port));
        properties.setProperty(IConfig.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");
        var server = new Server();
        var config = new MemoryConfig(properties);
        asUnchecked(() -> server.startServer(config));
        broker = server;
    }

    private static int findFreePort() {
        return getAsUnchecked(() -> {
            try (var socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            }
        });
    }
}
