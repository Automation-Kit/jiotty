package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.TextOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// End-to-end pipeline test: real [OptionRegistryImpl] + [DisplayableRegistryImpl] + [SseServiceImpl] wired together. Exercises the SSE broadcast surface,
/// option/displayable lifecycle events, heartbeat, hello frames, per-client snapshot delivery, and option persistence interactions. Handler-level behaviour for
/// individual `/api/*` endpoints is covered separately in per-handler tests.
@ExtendWith(MockitoExtension.class)
class SseServiceImplTest {
    private static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory()).registerModule(new JavaTimeModule());
    private static final Duration THROTTLING_PERIOD = Duration.ofMillis(100);

    private ProgrammableClock clock;
    @Mock
    private OptionPersistence persistence;

    private OptionRegistryImpl optionRegistry;
    private DisplayableRegistryImpl displayableRegistry;
    private SseServiceImpl sseService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("test");
        optionRegistry = new OptionRegistryImpl(persistence);
        displayableRegistry = new DisplayableRegistryImpl(executorProvider);
        meterRegistry = new SimpleMeterRegistry();
        sseService = new SseServiceImpl(executorProvider, optionRegistry, displayableRegistry, clock, THROTTLING_PERIOD, meterRegistry);
        optionRegistry.start();
        displayableRegistry.start();
        sseService.start();
        clock.tick();
    }

    @AfterEach
    void tearDown() {
        sseService.stop();
        displayableRegistry.stop();
        optionRegistry.stop();
        clock.tick();
    }

    // region displayable tests

    @Test
    void sseDeliversInitialDisplayableImageOnConnect() {
        displayableRegistry.register(createDisplayable("d1", "Display 1",
                                                       completedFuture(new HistoryDisplayableDto(Map.of("key", List.of())))));
        clock.tick();

        var capture = connectSseClient();

        String output = capture.output();
        assertThat(output).contains("event: hello");
        assertThat(output).contains("event: displayable-update");
        assertThat(output).contains("\"id\":\"d1\"");
    }

    @Test
    void connectingSseClient_recordsPostFlushDisplayablesAndOptionsSnapshotTimers() {
        displayableRegistry.register(createDisplayable("d1", "Display 1",
                                                       completedFuture(new HistoryDisplayableDto(Map.of("key", List.of())))));
        clock.tick();

        connectSseClient();

        assertThat(meterRegistry.find("sse_headers_to_snapshot_start_seconds").timer())
                .as("sse_headers_to_snapshot_start_seconds is recorded for every connecting SSE client")
                .isNotNull()
                .returns(1L, Timer::count);
        assertThat(meterRegistry.find("sse_displayables_snapshot_seconds").timer())
                .as("sse_displayables_snapshot_seconds is recorded once all displayables have flushed")
                .isNotNull()
                .returns(1L, Timer::count);
        assertThat(meterRegistry.find("sse_options_snapshot_seconds").timer())
                .as("sse_options_snapshot_seconds is recorded once the options snapshot has flushed")
                .isNotNull()
                .returns(1L, Timer::count);
    }

    @Test
    void displayableUpdateBroadcastsToAllSseClients() {
        var updateTrigger = new MutableReference<Runnable>();
        var displayable = createDisplayable("d1", "Display 1",
                                            completedFuture(new HistoryDisplayableDto(Map.of("key", List.of()))));
        when(displayable.supportsData()).thenReturn(true);
        when(displayable.subscribeForUpdates(any())).thenAnswer(invocation -> {
            updateTrigger.set(invocation.getArgument(0));
            return (Closeable) () -> {};
        });

        var capture1 = connectSseClient("host1", 1111);
        var capture2 = connectSseClient("host2", 2222);
        displayableRegistry.register(displayable);
        clock.tick();

        capture1.reset();
        capture2.reset();

        updateTrigger.get().run();
        clock.tick();
        clock.advanceTimeAndTick(Duration.ofSeconds(1));

        assertThat(capture1.output()).contains("event: displayable-update");
        assertThat(capture2.output()).contains("event: displayable-update");
    }

    @Test
    void displayableUpdateIsThrottled() {
        var updateTrigger = new MutableReference<Runnable>();
        var callCount = new int[]{0};
        var displayable = createDisplayable("d1", "Display 1", null);
        when(displayable.supportsData()).thenReturn(true);
        when(displayable.subscribeForUpdates(any())).thenAnswer(invocation -> {
            updateTrigger.set(invocation.getArgument(0));
            return (Closeable) () -> {};
        });
        when(displayable.toDto()).thenAnswer(_ -> {
            callCount[0]++;
            return completedFuture(new HistoryDisplayableDto(Map.of("key", List.of())));
        });

        displayableRegistry.register(displayable);
        clock.tick();

        var capture = connectSseClient();
        int baseCount = callCount[0];
        capture.reset();

        updateTrigger.get().run();
        clock.tick();
        updateTrigger.get().run();
        clock.tick();
        updateTrigger.get().run();
        clock.tick();

        int afterRapidCount = callCount[0] - baseCount;
        assertThat(afterRapidCount).isEqualTo(1);

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        int afterThrottleCount = callCount[0] - baseCount;
        assertThat(afterThrottleCount).isEqualTo(2);
    }

    @Test
    void displayableUnregistrationStopsUpdates() {
        var displayable = createDisplayable("d1", "Display 1",
                                            completedFuture(new HistoryDisplayableDto(Map.of("key", List.of()))));
        when(displayable.supportsData()).thenReturn(true);
        when(displayable.subscribeForUpdates(any())).thenReturn(() -> {});

        Closeable registration = displayableRegistry.register(displayable);
        clock.tick();

        var capture = connectSseClient();
        capture.reset();

        registration.close();
        clock.tick();

        String output = capture.output();
        assertThat(output).doesNotContain("event: displayable-update");
    }

    // endregion

    // region options SSE tests

    @Test
    void optionRegistrationBroadcastsOptionsUpdateWithThrottling() {
        SseCapture capture = connectSseClient();
        // Drain the startup-image throttle window so the registration below starts the next throttle cycle fresh.
        clock.advanceTimeAndTick(THROTTLING_PERIOD);
        capture.reset();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.tick();
        registerTestOption("tab1", "opt2", "Option 2");
        clock.tick();

        String output = capture.output();
        assertThat(output).contains("event: options-update").contains("\"tabs\"").contains("\"opt1\"").doesNotContain("\"opt2\"");
        capture.reset();

        assertThat(capture.output()).doesNotContain("event: options-update");

        clock.advanceTimeAndTick(THROTTLING_PERIOD.dividedBy(2));
        assertThat(capture.output()).doesNotContain("event: options-update");

        clock.advanceTimeAndTick(THROTTLING_PERIOD.dividedBy(2));
        output = capture.output();
        assertThat(output).contains("event: options-update").contains("\"tabs\"").contains("\"opt1\"").contains("\"opt2\"");
    }

    @Test
    void burstOptionRegistrationsProduceTwoSseEvents() {
        SseCapture capture = connectSseClient();
        // Drain the startup-image throttle window so the first registration below starts the next throttle cycle fresh.
        clock.advanceTimeAndTick(THROTTLING_PERIOD);
        capture.reset();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD.dividedBy(10));
        assertThat(countOccurrences(capture.output(), "event: options-update")).isEqualTo(1);
        capture.reset();
        registerTestOption("tab1", "opt2", "Option 2");
        clock.advanceTimeAndTick(THROTTLING_PERIOD.dividedBy(10));
        registerTestOption("tab2", "opt3", "Option 3");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        String output = capture.output();
        assertThat(countOccurrences(output, "event: options-update")).isEqualTo(1);

        assertThat(output).contains("\"opt1\"");
        assertThat(output).contains("\"opt2\"");
        assertThat(output).contains("\"opt3\"");
    }

    @Test
    void optionUnregistrationBroadcastsUpdatedList() {
        Closeable reg1 = registerTestOption("tab1", "opt1", "Option 1");
        registerTestOption("tab1", "opt2", "Option 2");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        var capture = connectSseClient();
        capture.reset();

        reg1.close();
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        String output = capture.output();
        assertThat(output).contains("event: options-update");
        String eventData = extractSseEventData(output, "options-update");
        assertThat(eventData).doesNotContain("\"opt1\"");
        assertThat(eventData).contains("\"opt2\"");
    }

    @Test
    void sseConnectDeliversInitialOptionsImage() {
        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        var capture = connectSseClient();

        String output = capture.output();
        assertThat(output).contains("event: options-update");
        assertThat(output).contains("\"opt1\"");
    }

    @Test
    void optionsUpdateJsonStructure() {
        registerTestOption("MyTab", "my.key", "My Label");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        var capture = connectSseClient();
        String eventData = extractSseEventData(capture.output(), "options-update");
        Map<String, Object> parsed = parseJson(eventData);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) parsed.get("tabs");
        assertThat(tabs).hasSize(1);
        assertThat(tabs.getFirst().get("name")).isEqualTo("MyTab");
        assertThat(tabs.getFirst().get("id")).isEqualTo("MyTab");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> options = (List<Map<String, Object>>) tabs.getFirst().get("options");
        assertThat(options).hasSize(1);
        assertThat(options.getFirst().get("key")).isEqualTo("my.key");
        assertThat(options.getFirst().get("label")).isEqualTo("My Label");
        assertThat(options.getFirst().get("type")).isEqualTo("text");
    }

    @Test
    void optionFormPostBroadcastsOptionUpdateViaSse() {
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("post");
        var handler = new OptionsPostHandler(optionRegistry, executorProvider);
        handler.start();
        clock.tick();
        try {
            var option = createTestOption("tab1", "opt1", "Option 1");
            optionRegistry.register(option);
            clock.advanceTimeAndTick(THROTTLING_PERIOD);

            var capture = connectSseClient();
            capture.reset();

            var request = mock(HttpServletRequest.class);
            var response = mock(HttpServletResponse.class);
            var asyncContext = mock(AsyncContext.class);
            var responseBody = new StringWriter();
            when(request.getMethod()).thenReturn("POST");
            when(request.startAsync()).thenReturn(asyncContext);
            doAnswer(invocation -> {
                invocation.getArgument(0, Runnable.class).run();
                return null;
            }).when(asyncContext).start(any(Runnable.class));
            when(request.getParameter("name")).thenReturn("opt1");
            when(request.getParameter("value")).thenReturn("new value");
            lenient().when(request.getParameterMap()).thenReturn(Map.of());
            asUnchecked(() -> when(response.getWriter()).thenReturn(new PrintWriter(responseBody)));

            handler.handle(request, response);
            clock.advanceTimeAndTick(THROTTLING_PERIOD);

            String output = capture.output();
            assertThat(output).contains("event: options-update");
            String eventData = extractSseEventData(output, "options-update");
            assertThat(eventData).contains("\"opt1\"");
            assertThat(eventData).contains("new value");
        } finally {
            handler.stop();
            clock.tick();
        }
    }

    @Test
    void programmaticSetValueBroadcastsOptionUpdateViaSse() {
        var option = createTestOption("tab1", "opt1", "Option 1");
        optionRegistry.register(option);
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        var capture = connectSseClient();
        capture.reset();

        option.setValue("programmatic value");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        String output = capture.output();
        assertThat(output).contains("event: options-update");
        String eventData = extractSseEventData(output, "options-update");
        assertThat(eventData).contains("\"opt1\"");
        assertThat(eventData).contains("programmatic value");
    }

    // endregion

    // region SSE heartbeat

    @Test
    void sseHeartbeatSentEvery15Seconds() {
        var capture = connectSseClient();
        capture.reset();

        clock.advanceTimeAndTick(Duration.ofSeconds(15));

        assertThat(capture.output()).contains("event: ping");
    }

    @Test
    void heartbeatPingIncludesCurrentServerTimeOnEachTick() {
        var capture = connectSseClient();
        capture.reset();

        clock.advanceTimeAndTick(Duration.ofSeconds(15));
        var firstPingTime = clock.currentInstant();
        var firstData = parseJson(extractSseEventData(capture.output(), "ping"));
        assertThat(MAPPER.convertValue(firstData.get("serverTime"), Instant.class)).isEqualTo(firstPingTime);

        capture.reset();
        clock.advanceTimeAndTick(Duration.ofSeconds(15));
        var secondPingTime = clock.currentInstant();
        var secondData = parseJson(extractSseEventData(capture.output(), "ping"));
        assertThat(MAPPER.convertValue(secondData.get("serverTime"), Instant.class)).isEqualTo(secondPingTime);
        assertThat(secondPingTime).isNotEqualTo(firstPingTime);
    }

    // endregion

    // region SSE client lifecycle

    @Test
    void sseClientDisconnectRemovesClient() {
        var capture = connectSseClient();
        capture.reset();

        asUnchecked(() -> capture.asyncListener.get().onComplete(null));
        clock.tick();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);
        // mainly verifies no exceptions are thrown after the disconnected client is removed
    }

    @Test
    void closeReturnedFromStartSseClosesStream() {
        var capture = connectSseClient();
        capture.reset();

        capture.closeHandle.get().close();
        clock.tick();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);
    }

    @Test
    void doStopClosesSseClients() {
        var onStreamClosed = mock(Runnable.class);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var asyncContext = mock(AsyncContext.class);
        var capture = new SseCapture();

        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getRemotePort()).thenReturn(12345);
        when(request.startAsync()).thenReturn(asyncContext);
        lenient().when(asyncContext.getResponse()).thenReturn(response);
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(capture.outputStream()));

        asUnchecked(() -> sseService.startSse(request, response, onStreamClosed));
        clock.tick();

        sseService.stop();
        clock.tick();

        verify(onStreamClosed).run();
    }

    @Test
    void closeHandleAfterStopIsNoOpAndDoesNotThrow() {
        var capture = connectSseClient();
        // Terminate the service's executor, mirroring component shutdown while a stream close is still pending. The returned close handle must then be a silent
        // no-op, not a RejectedExecutionException scheduled onto the terminated executor.
        sseService.stop();
        clock.tick();

        assertThatCode(() -> capture.closeHandle.get().close()).doesNotThrowAnyException();
    }

    @Test
    void startSseInitialImageDrainedAfterStopClosesClientWithoutReadingRegistries() {
        var onStreamClosed = mock(Runnable.class);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var asyncContext = mock(AsyncContext.class);
        var capture = new SseCapture();

        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getRemotePort()).thenReturn(12345);
        when(request.startAsync()).thenReturn(asyncContext);
        lenient().when(asyncContext.getResponse()).thenReturn(response);
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(capture.outputStream()));

        // Enqueue the initial-image task but do not tick, so it stays queued on the executor.
        asUnchecked(() -> sseService.startSse(request, response, onStreamClosed));

        // Tear down in reverse dependency order — the SSE service first, then the registries it reads — mirroring per-user injector teardown, and only now tick
        // so the queued initial-image task drains after every component has stopped. It must skip all work and close the freshly-created client, not read the
        // stopped registries.
        sseService.stop();
        displayableRegistry.stop();
        optionRegistry.stop();
        clock.tick();

        // The drained task never initialised the stream (no hello/snapshot frames), proving it took the stopped-service short-circuit instead of reading the
        // registries. onStreamClosed still fires so the connection is not leaked.
        assertThat(capture.output()).isEmpty();
        verify(onStreamClosed).run();
    }

    @Test
    void sseClientCloseIsIdempotentAcrossMultipleTriggers() {
        var onStreamClosed = mock(Runnable.class);
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var asyncContext = mock(AsyncContext.class);
        var capture = new SseCapture();

        when(request.getRemoteHost()).thenReturn("localhost");
        when(request.getRemotePort()).thenReturn(12345);
        when(request.startAsync()).thenReturn(asyncContext);
        lenient().when(asyncContext.getResponse()).thenReturn(response);
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(capture.outputStream()));
        doAnswer(invocation -> {
            capture.asyncListener.set(invocation.getArgument(0));
            return null;
        }).when(asyncContext).addListener(any(AsyncListener.class));

        var handle = getAsUnchecked(() -> sseService.startSse(request, response, onStreamClosed));
        clock.tick();

        handle.close();
        clock.tick();
        asUnchecked(() -> capture.asyncListener.get().onComplete(null));
        clock.tick();

        verify(asyncContext, times(1)).complete();
        verify(onStreamClosed, times(1)).run();
    }

    // endregion

    // region multiple SSE clients

    @Test
    void broadcastReachesAllConnectedClients() {
        var capture1 = connectSseClient("host1", 1111);
        var capture2 = connectSseClient("host2", 2222);
        capture1.reset();
        capture2.reset();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        assertThat(capture1.output()).contains("event: options-update");
        assertThat(capture2.output()).contains("event: options-update");
    }

    @Test
    void newClientConnectionDoesNotRebroadcastDisplayablesToExistingClients() {
        displayableRegistry.register(createDisplayable("d1", "Display 1",
                                                       completedFuture(new HistoryDisplayableDto(Map.of("key", List.of())))));
        clock.tick();

        var capture1 = connectSseClient("host1", 1111);
        assertThat(capture1.output()).contains("event: displayable-update");
        capture1.reset();

        connectSseClient("host2", 2222);

        assertThat(capture1.output()).doesNotContain("event: displayable-update");
    }

    @Test
    void newClientConnectionDoesNotRebroadcastOptionsToExistingClients() {
        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        var capture1 = connectSseClient("host1", 1111);
        assertThat(capture1.output()).contains("event: options-update");
        capture1.reset();

        connectSseClient("host2", 2222);

        assertThat(capture1.output()).doesNotContain("event: options-update");
    }

    // endregion

    // region option persistence

    @Test
    void optionRegistrationLoadsPersistence() {
        var option = createTestOption("tab1", "opt1", "Label");
        optionRegistry.register(option);
        verify(persistence).load(option);
    }

    @Test
    void optionValueChangeTriggersPersistenceSave() {
        var option = createTestOption("tab1", "opt1", "Label");
        optionRegistry.register(option);
        clock.tick();
        reset(persistence);

        option.setValue("new value");
        clock.tick();

        verify(persistence).save(option);
    }

    // endregion

    // region SSE hello event

    @Test
    void sseHelloEventContainsClientIdSeqNum() {
        var capture = connectSseClient();
        String output = capture.output();
        assertThat(output).contains("event: hello");
        assertThat(output).contains("clientIdSeqNum");
    }

    @Test
    void sseClientIdsAreSequential() {
        var capture1 = connectSseClient("host1", 1111);
        var capture2 = connectSseClient("host2", 2222);

        String hello1 = extractSseEventData(capture1.output(), "hello");
        String hello2 = extractSseEventData(capture2.output(), "hello");

        Map<String, Object> data1 = parseJson(hello1);
        Map<String, Object> data2 = parseJson(hello2);
        int seq1 = ((Number) data1.get("clientIdSeqNum")).intValue();
        int seq2 = ((Number) data2.get("clientIdSeqNum")).intValue();
        assertThat(seq2).isEqualTo(seq1 + 1);
    }

    @Test
    void helloEventIncludesServerTime() {
        var capture = connectSseClient();

        var data = parseJson(extractSseEventData(capture.output(), "hello"));
        assertThat(data).containsKey("clientIdSeqNum");
        assertThat(MAPPER.convertValue(data.get("serverTime"), Instant.class)).isEqualTo(clock.currentInstant());
    }

    // endregion

    // region displayable + options combined initial image

    @Test
    void sseConnectDeliversBothDisplayablesAndOptions() {
        displayableRegistry.register(createDisplayable("d1", "Display 1",
                                                       completedFuture(new HistoryDisplayableDto(Map.of("key", List.of())))));
        clock.tick();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        var capture = connectSseClient();
        String output = capture.output();

        assertThat(output).contains("event: displayable-update");
        assertThat(output).contains("event: options-update");
    }

    // endregion

    // region edge cases

    @Test
    void emptyOptionsProducesEmptyTabsArray() {
        var capture = connectSseClient();
        String eventData = extractSseEventData(capture.output(), "options-update");
        Map<String, Object> parsed = parseJson(eventData);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) parsed.get("tabs");
        assertThat(tabs).isEmpty();
    }

    @Test
    void displayableDtoFailureDoesNotBreakSseStream() {
        displayableRegistry.register(createDisplayable("d1", "Display 1",
                                                       CompletableFuture.failedFuture(new RuntimeException("DTO generation failed"))));
        clock.tick();

        var capture = connectSseClient();

        capture.reset();
        clock.advanceTimeAndTick(Duration.ofSeconds(15));
        assertThat(capture.output()).contains("event: ping");
    }

    @Test
    void optionTabIdSanitisesSpecialCharacters() {
        registerTestOption("My Tab!", "opt1", "Label");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        var capture = connectSseClient();
        String eventData = extractSseEventData(capture.output(), "options-update");
        Map<String, Object> parsed = parseJson(eventData);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) parsed.get("tabs");
        assertThat(tabs.getFirst().get("id")).isEqualTo("My-Tab-");
    }

    // endregion

    // region helpers

    private SseCapture connectSseClient() {
        return connectSseClient("localhost", 12345);
    }

    private SseCapture connectSseClient(String host, int port) {
        var capture = new SseCapture();
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var asyncContext = mock(AsyncContext.class);

        when(request.getRemoteHost()).thenReturn(host);
        when(request.getRemotePort()).thenReturn(port);
        when(request.startAsync()).thenReturn(asyncContext);
        lenient().when(asyncContext.getResponse()).thenReturn(response);
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(capture.outputStream()));
        doAnswer(invocation -> {
            capture.asyncListener.set(invocation.getArgument(0));
            return null;
        }).when(asyncContext).addListener(any(AsyncListener.class));

        capture.closeHandle.set(getAsUnchecked(() -> sseService.startSse(request, response, capture.closedRunnable)));
        clock.tick();
        return capture;
    }

    private Closeable registerTestOption(String tabName, String key, String label) {
        return optionRegistry.register(createTestOption(tabName, key, label));
    }

    private TestTextOption createTestOption(String tabName, String key, String label) {
        return new TestTextOption(clock.createSingleThreadedSchedulingExecutor("opt-" + key),
                                  OptionMeta.<String>builder()
                                            .setFormOrder(Option.DEFAULT_FORM_ORDER)
                                            .setTabName(tabName)
                                            .setKey(key)
                                            .setLabel(label)
                                            .build());
    }

    private static Displayable createDisplayable(String id, String displayName, CompletableFuture<DisplayableDto> dto) {
        var displayable = mock(Displayable.class);
        when(displayable.getId()).thenReturn(id);
        lenient().when(displayable.getDisplayName()).thenReturn(displayName);
        lenient().when(displayable.supportsData()).thenReturn(false);
        lenient().when(displayable.visible()).thenReturn(true);
        if (dto != null) {
            when(displayable.toDto()).thenReturn(dto);
        }
        return displayable;
    }

    private static String extractSseEventData(String sseOutput, String eventName) {
        String marker = "event: " + eventName + "\n";
        int eventStart = sseOutput.lastIndexOf(marker);
        if (eventStart < 0) {
            return "";
        }
        int dataStart = sseOutput.indexOf("data: ", eventStart);
        if (dataStart < 0) {
            return "";
        }
        int dataEnd = sseOutput.indexOf('\n', dataStart + 6);
        if (dataEnd < 0) {
            return sseOutput.substring(dataStart + 6);
        }
        return sseOutput.substring(dataStart + 6, dataEnd);
    }

    private static long countOccurrences(String text, String substring) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) >= 0) {
            count++;
            idx += substring.length();
        }
        return count;
    }

    private static Map<String, Object> parseJson(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }

    // endregion

    // region test infrastructure

    private static final class TestTextOption extends TextOption {
        TestTextOption(TaskExecutor executor, OptionMeta<String> meta) {
            super(executor, meta);
        }

        @Override
        public String onChanged() {
            return getValue().orElse(null);
        }
    }

    private static final class SseCapture {
        final MutableReference<AsyncListener> asyncListener = new MutableReference<>();
        final MutableReference<Closeable> closeHandle = new MutableReference<>();
        final Runnable closedRunnable = () -> {};
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);

        String output() {
            return buffer.toString();
        }

        void reset() {
            buffer.reset();
        }

        ServletOutputStream outputStream() {
            return new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                }

                @Override
                public void write(int b) {
                    buffer.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    buffer.write(b, off, len);
                }

                @Override
                public void print(String s) {
                    byte[] bytes = s.getBytes();
                    buffer.write(bytes, 0, bytes.length);
                }

                @Override
                public void flush() {
                }
            };
        }
    }

    // endregion
}
