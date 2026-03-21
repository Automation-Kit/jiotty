package net.yudichev.jiotty.connector.sonyprojector;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.toIntExact;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

public final class SonyProjectorClientImpl extends BaseLifecycleComponent implements SonyProjectorClient {
    private static final Logger logger = LogManager.getLogger(SonyProjectorClientImpl.class);

    private static final Charset CHARSET = StandardCharsets.US_ASCII;
    private static final String COMMAND_TERMINATOR = "\r\n";
    private static final String POWER_ON_COMMAND = "power \"on\"";
    private static final String POWER_OFF_COMMAND = "power \"off\"";
    private static final String POWER_STATUS_COMMAND = "power_status ?";

    private final ExecutorFactory executorFactory;
    private final String host;
    private final int port;
    private final Duration timeout;
    private final Optional<String> password;

    private SchedulingExecutor executor;

    @Inject
    public SonyProjectorClientImpl(ExecutorFactory executorFactory,
                                   @Host String host,
                                   @Port int port,
                                   @Timeout Duration timeout,
                                   @Password Optional<String> password) {
        this.executorFactory = checkNotNull(executorFactory);
        this.host = checkNotNull(host);
        checkArgument(!host.isBlank(), "host must not be blank");
        checkArgument(port > 0 && port <= 65535, "port must be in range 1-65535, but was %s", port);
        this.port = port;
        this.timeout = checkNotNull(timeout);
        checkArgument(timeout.isPositive(), "timeout must be positive, but was %s", timeout);
        this.password = checkNotNull(password);
        password.ifPresent(s -> checkArgument(!s.isBlank(), "password must not be blank"));
    }

    @Override
    public CompletableFuture<Void> powerOn() {
        return sendCommand(POWER_ON_COMMAND).thenApply(SonyProjectorClientImpl::ensureOkResponse);
    }

    @Override
    public CompletableFuture<Void> powerOff() {
        return sendCommand(POWER_OFF_COMMAND).thenApply(SonyProjectorClientImpl::ensureOkResponse);
    }

    @Override
    public CompletableFuture<SonyProjectorPowerState> getPowerState() {
        return sendCommand(POWER_STATUS_COMMAND).thenApply(SonyProjectorProtocol::parsePowerStatusResponse);
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("sony-projector-client");
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, executor);
    }

    private CompletableFuture<String> sendCommand(String command) {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> sendCommandBlocking(command)));
    }

    private static Void ensureOkResponse(String response) {
        checkState(SonyProjectorProtocol.isOkResponse(response), "Unexpected projector response: %s", response);
        return null;
    }

    private String sendCommandBlocking(String command) {
        logger.debug("Sending projector command {}", command);
        return getAsUnchecked(() -> {
            int timeoutMillis = toIntExact(timeout.toMillis());
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeoutMillis);
                socket.setSoTimeout(timeoutMillis);
                socket.setKeepAlive(true);
                try (var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), CHARSET));
                     var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), CHARSET))) {
                    String handshake = readLine(reader, "handshake");
                    if (!SonyProjectorProtocol.isNoKeyResponse(handshake)) {
                        authenticate(handshake, reader, writer);
                    }
                    writeLine(writer, command);
                    String response = readLine(reader, "command response");
                    logger.debug("Projector response for {}: {}", command, response);
                    return response;
                }
            }
        });
    }

    private void authenticate(String challenge, BufferedReader reader, BufferedWriter writer) {
        checkState(password.isPresent(), "Projector requested authentication but no password is configured");
        String authorisation = SonyProjectorProtocol.computeAuthorisation(challenge.trim(), password.orElseThrow());
        writeLine(writer, authorisation);
        String authResponse = readLine(reader, "authentication response");
        checkState(SonyProjectorProtocol.isAuthOkResponse(authResponse), "Projector authentication failed: %s", authResponse);
    }

    private static void writeLine(BufferedWriter writer, String command) {
        getAsUnchecked(() -> {
            writer.write(command);
            writer.write(COMMAND_TERMINATOR);
            writer.flush();
            return null;
        });
    }

    private static String readLine(BufferedReader reader, String description) {
        return getAsUnchecked(() -> {
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalStateException("Connection closed while waiting for " + description);
            }
            return line;
        });
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Host {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Port {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Timeout {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Password {
    }
}
