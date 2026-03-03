package net.yudichev.jiotty.connector.sonyprojector;

import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static com.google.common.base.Throwables.getRootCause;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class SonyProjectorClientImplTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private FakeSonyProjectorServer server;
    private SonyProjectorClientImpl client;

    @AfterEach
    void tearDown() {
        Closeable.forActions(
                         () -> {
                             if (client != null) {
                                 client.stop();
                             }
                         },
                         () -> Closeable.closeIfNotNull(server))
                 .close();
    }

    @Test
    void powersOnUsingNoKeyHandshake() throws Exception {
        server = FakeSonyProjectorServer.start((reader, writer) -> {
            FakeSonyProjectorServer.writeLine(writer, "NOKEY");
            assertThat(FakeSonyProjectorServer.readLine(reader)).isEqualTo("power \"on\"");
            FakeSonyProjectorServer.writeLine(writer, "ok");
        });
        client = startClient(Optional.empty(), server.port());

        client.powerOn().get(2, SECONDS);

        server.awaitCompletion(TIMEOUT);
    }

    @Test
    void powersOffUsingAuthentication() throws Exception {
        String challenge = "ABCDEF";
        String password = "secret";
        server = FakeSonyProjectorServer.start((reader, writer) -> {
            FakeSonyProjectorServer.writeLine(writer, challenge);
            assertThat(FakeSonyProjectorServer.readLine(reader))
                    .isEqualTo(SonyProjectorProtocol.computeAuthorisation(challenge, password));
            FakeSonyProjectorServer.writeLine(writer, "OK");
            assertThat(FakeSonyProjectorServer.readLine(reader)).isEqualTo("power \"off\"");
            FakeSonyProjectorServer.writeLine(writer, "ok");
        });
        client = startClient(Optional.of(password), server.port());

        client.powerOff().get(2, SECONDS);

        server.awaitCompletion(TIMEOUT);
    }

    @Test
    void readsPowerState() throws Exception {
        server = FakeSonyProjectorServer.start((reader, writer) -> {
            FakeSonyProjectorServer.writeLine(writer, "NOKEY");
            assertThat(FakeSonyProjectorServer.readLine(reader)).isEqualTo("power_status ?");
            FakeSonyProjectorServer.writeLine(writer, "power_status \"on\"");
        });
        client = startClient(Optional.empty(), server.port());

        var state = client.getPowerState().get(2, SECONDS);

        assertThat(state).isEqualTo(SonyProjectorPowerState.ON);
        server.awaitCompletion(TIMEOUT);
    }

    @Test
    void failsWhenAuthenticationRequiredButPasswordMissing() {
        server = FakeSonyProjectorServer.start((_, writer) -> FakeSonyProjectorServer.writeLine(writer, "ABCDEF"));
        client = startClient(Optional.empty(), server.port());

        var future = client.powerOn();

        var thrown = catchThrowable(() -> future.get(2, SECONDS));

        assertThat(getRootCause(thrown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Projector requested authentication but no password is configured");
        server.awaitCompletion(TIMEOUT);
    }

    @Test
    void failsWhenAuthenticationRejected() {
        server = FakeSonyProjectorServer.start((reader, writer) -> {
            FakeSonyProjectorServer.writeLine(writer, "ABCDEF");
            FakeSonyProjectorServer.readLine(reader);
            FakeSonyProjectorServer.writeLine(writer, "err_auth");
        });
        client = startClient(Optional.of("secret"), server.port());

        var future = client.powerOn();

        var thrown = catchThrowable(() -> future.get(2, SECONDS));

        assertThat(getRootCause(thrown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Projector authentication failed: err_auth");
        server.awaitCompletion(TIMEOUT);
    }

    @Test
    void failsWhenCommandResponseIsUnexpected() {
        server = FakeSonyProjectorServer.start((reader, writer) -> {
            FakeSonyProjectorServer.writeLine(writer, "NOKEY");
            assertThat(FakeSonyProjectorServer.readLine(reader)).isEqualTo("power \"on\"");
            FakeSonyProjectorServer.writeLine(writer, "err_cmd");
        });
        client = startClient(Optional.empty(), server.port());

        var future = client.powerOn();

        var thrown = catchThrowable(() -> future.get(2, SECONDS));

        assertThat(getRootCause(thrown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected projector response: err_cmd");
        server.awaitCompletion(TIMEOUT);
    }

    private static SonyProjectorClientImpl startClient(Optional<String> password, int port) {
        ExecutorFactory executorFactory = SingleThreadedSchedulingExecutor::new;
        var client = new SonyProjectorClientImpl(executorFactory, "127.0.0.1", port, TIMEOUT, password);
        client.start();
        return client;
    }
}
