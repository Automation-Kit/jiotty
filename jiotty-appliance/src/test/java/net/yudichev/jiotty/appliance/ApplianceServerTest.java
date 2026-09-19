package net.yudichev.jiotty.appliance;

import net.yudichev.jiotty.common.rest.JavalinRestServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplianceServerTest {
    private static final Logger logger = LogManager.getLogger(ApplianceServerTest.class);

    private static final String APPLIANCE_ID = "curtain";
    private static final String POSITION_COMMAND = "POSITION";
    private static final String SCHEDULE_COMMAND = "SCHEDULE";
    private static final String BROKEN_COMMAND = "BROKEN";
    private static final String POSITION_PATH = ApplianceServer.urlFor(APPLIANCE_ID, POSITION_COMMAND);
    private static final String SCHEDULE_PATH = ApplianceServer.urlFor(APPLIANCE_ID, SCHEDULE_COMMAND);
    private static final String BROKEN_PATH = ApplianceServer.urlFor(APPLIANCE_ID, BROKEN_COMMAND);
    private static final String OUT_OF_BOUNDS_MESSAGE = "position must be within [0.0, 1.0] bounds";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final String LOOPBACK = "127.0.0.1";
    /// Written on the Jetty request thread that serves each POST and drained on the JUnit thread that asserts.
    private final Queue<LogEvent> logEvents = new ConcurrentLinkedQueue<>();
    @Mock
    private Appliance appliance;
    private LoggerContext loggerContext;
    private CapturingAppender appender;
    private JavalinRestServer restServer;
    private ApplianceServer applianceServer;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        captureApplianceServerLog();
        when(appliance.getAllSupportedCommandMetadata())
                .thenReturn(Set.of(createPositionCommandMeta(), createScheduleCommandMeta(), createBrokenCommandMeta()));
        restServer = new JavalinRestServer(0, Optional.of(LOOPBACK));
        applianceServer = new ApplianceServer(restServer, APPLIANCE_ID, appliance);
        restServer.start();
        applianceServer.start();
        httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    /// Reverse of the order [#setUp] built them in, and through the helper so every step runs even when an earlier one throws — the Jetty port and the
    /// attached capture both outlive this test otherwise.
    @AfterEach
    void tearDown() {
        closeSafelyIfNotNull(logger,
                             httpClient,
                             applianceServer == null ? null : applianceServer::stop,
                             restServer == null ? null : restServer::stop,
                             loggerContext == null ? null : this::detachApplianceServerLog);
    }

    static Stream<Arguments> executesCommandBuiltFromRequestParameters() {
        return Stream.of(arguments("done", "{\"success\":\"true\",\"response\":\"done\"}"),
                         arguments(null, "{\"success\":\"true\"}"));
    }

    @ParameterizedTest
    @MethodSource
    void executesCommandBuiltFromRequestParameters(String commandResult, String expectedBody) throws Exception {
        doReturn(completedFuture(commandResult)).when(appliance).execute(any());

        HttpResponse<String> response = post(POSITION_PATH, "?pos=0.6");

        assertThat(response.statusCode()).isEqualTo(OK_200);
        assertThat(response.body()).isEqualTo(expectedBody);
        verify(appliance).execute(new PositionCommand(0.6));
    }

    @Test
    void namesItselfAfterTheAppliance() {
        when(appliance.name()).thenReturn("Test Curtain");

        assertThat(applianceServer.name()).contains("Test Curtain");
    }

    /// The `?time=` rows are the ones that matter: [LocalTime#parse] throws [java.time.format.DateTimeParseException], which is no
    /// [IllegalArgumentException], and a decoder like it is what a real appliance registers.
    static Stream<Arguments> rejectsMalformedRequestWithItsReason() {
        return Stream.of(arguments(POSITION_PATH, "?pos=1.5", List.of("\"success\":\"false\"", OUT_OF_BOUNDS_MESSAGE)),
                         arguments(POSITION_PATH, "?pos=halfway", List.of("Failed decoding parameter pos", "halfway")),
                         arguments(POSITION_PATH, "", List.of("Missing required parameter 'pos'")),
                         arguments(SCHEDULE_PATH, "?time=25:00", List.of("Failed decoding parameter time", "25:00")),
                         arguments(SCHEDULE_PATH, "", List.of("Missing required parameter 'time'")));
    }

    @ParameterizedTest
    @MethodSource
    void rejectsMalformedRequestWithItsReason(String path, String query, List<String> expectedBodyFragments) throws Exception {
        HttpResponse<String> response = post(path, query);

        assertThat(response.statusCode()).isEqualTo(BAD_REQUEST_400);
        assertThat(response.body()).contains(expectedBodyFragments);
        verify(appliance, never()).execute(any());
    }

    @Test
    void logsRejectionBelowTheLevelThatAlerts() throws Exception {
        post(POSITION_PATH, "?pos=1.5");

        assertThat(logEvents).extracting(LogEvent::getLevel).containsOnly(Level.INFO);
        assertThat(logEvents).extracting(event -> event.getMessage().getFormattedMessage())
                             .anySatisfy(message -> assertThat(message).contains(APPLIANCE_ID, "rejecting", POSITION_PATH, OUT_OF_BOUNDS_MESSAGE));
    }

    @Test
    void keepsACommandMetadataDefectOpaqueToTheCaller() throws Exception {
        HttpResponse<String> response = post(BROKEN_PATH, "");

        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR_500);
        assertThat(response.body()).contains("INTERNAL_ERROR").doesNotContain("bad command metadata");
        verify(appliance, never()).execute(any());
    }

    @Test
    void keepsExecutionFailureOpaqueToTheCaller() throws Exception {
        doReturn(failure(new RuntimeException("connector exploded"))).when(appliance).execute(any());

        HttpResponse<String> response = post(POSITION_PATH, "?pos=0.6");

        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR_500);
        assertThat(response.body()).contains("INTERNAL_ERROR").doesNotContain("connector exploded");
    }

    static Stream<Arguments> keepsASynchronousExecutionThrowOpaqueToTheCaller() {
        return Stream.of(arguments(new IllegalStateException("component is not started")),
                         arguments(new IllegalArgumentException("Command SetCurtainPosition:0.6 is not supported")));
    }

    /// An appliance may reject or fail a command on the calling thread — a lifecycle check, an unsupported command — which reaches the handler as a throw
    /// rather than as a failed future. The [IllegalArgumentException] row matters most: the same type means "your request was wrong" from command construction,
    /// and an internal fault from here.
    @ParameterizedTest
    @MethodSource
    void keepsASynchronousExecutionThrowOpaqueToTheCaller(RuntimeException thrown) throws Exception {
        doThrow(thrown).when(appliance).execute(any());

        HttpResponse<String> response = post(POSITION_PATH, "?pos=0.6");

        assertThat(response.statusCode()).isEqualTo(INTERNAL_SERVER_ERROR_500);
        assertThat(response.body()).contains("INTERNAL_ERROR").doesNotContain(thrown.getMessage());
    }

    private HttpResponse<String> post(String path, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + LOOPBACK + ':' + restServer.port() + path + query))
                                         .timeout(HTTP_TIMEOUT)
                                         .POST(HttpRequest.BodyPublishers.noBody())
                                         .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void detachApplianceServerLog() {
        loggerContext.getConfiguration().removeLogger(ApplianceServer.class.getName());
        loggerContext.updateLoggers();
        appender.stop();
    }

    /// Renders the server's own lines into [#logEvents]. The build pins the root level to [Level#OFF], so the capture needs a logger config of its own, and
    /// non-additivity sends them there alone.
    private void captureApplianceServerLog() {
        loggerContext = (LoggerContext) LogManager.getContext(false);
        appender = new CapturingAppender(logEvents);
        appender.start();
        var loggerConfig = new LoggerConfig(ApplianceServer.class.getName(), Level.INFO, false);
        loggerConfig.addAppender(appender, null, null);
        loggerContext.getConfiguration().addLogger(ApplianceServer.class.getName(), loggerConfig);
        loggerContext.updateLoggers();
    }

    private static CommandMeta<PositionCommand> createPositionCommandMeta() {
        return CommandMeta.<PositionCommand>builder()
                          .setCommandName(POSITION_COMMAND)
                          .putParameterTypes("pos", Double::parseDouble)
                          .setCommandFactory(params -> new PositionCommand((Double) params.get("pos")))
                          .build();
    }

    /// Registers a decoder whose failure is no [IllegalArgumentException], which is what a real appliance does — automator's washing-machine schedule takes
    /// [LocalTime#parse].
    private static CommandMeta<ScheduleCommand> createScheduleCommandMeta() {
        return CommandMeta.<ScheduleCommand>builder()
                          .setCommandName(SCHEDULE_COMMAND)
                          .putParameterTypes("time", LocalTime::parse)
                          .setCommandFactory(params -> new ScheduleCommand((LocalTime) params.get("time")))
                          .build();
    }

    /// Stands for an appliance whose own command metadata is the defect.
    private static CommandMeta<PositionCommand> createBrokenCommandMeta() {
        return CommandMeta.<PositionCommand>builder()
                          .setCommandName(BROKEN_COMMAND)
                          .setCommandFactory(_ -> {
                              throw new IllegalStateException("bad command metadata");
                          })
                          .build();
    }

    private record ScheduleCommand(LocalTime time) implements Command<ScheduleCommand> {
        @Override
        public <U> Optional<U> accept(Command.Visitor<U> visitor) {
            return Optional.empty();
        }
    }

    private record PositionCommand(double position) implements Command<PositionCommand> {
        PositionCommand {
            checkArgument(position >= 0.0 && position <= 1.0, OUT_OF_BOUNDS_MESSAGE);
        }

        @Override
        public <U> Optional<U> accept(Command.Visitor<U> visitor) {
            return Optional.empty();
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final Queue<LogEvent> events;

        CapturingAppender(Queue<LogEvent> events) {
            super("capturingAppender", null, null, true, Property.EMPTY_ARRAY);
            this.events = checkNotNull(events);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
