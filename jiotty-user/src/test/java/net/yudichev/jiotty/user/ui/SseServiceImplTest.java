package net.yudichev.jiotty.user.ui;

import com.google.common.reflect.TypeToken;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionDto;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.TextOption;
import net.yudichev.jiotty.user.ui.sse.SseChannel;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import net.yudichev.jiotty.user.ui.sse.testing.SseChannels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.user.ui.sse.testing.SseFrames.dataOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// End-to-end pipeline test: real [OptionRegistryImpl] + [DisplayableRegistryImpl] + [SseServiceImpl] wired together, covering option/displayable lifecycle
/// events, throttling, per-client snapshot delivery, snapshot timers, and option persistence. The transport carrying them is covered by [SseChannel]'s test.
@ExtendWith(MockitoExtension.class)
class SseServiceImplTest {
    private static final Duration THROTTLING_PERIOD = Duration.ofMillis(100);

    private static final String USER_ID = "user-1";
    /// Deadlock safety net for a read parked on another thread, long enough that a loaded machine never trips it.
    private static final Duration PARKED_READ_TIMEOUT = Duration.ofSeconds(5);
    /// How long a stop is watched before concluding it is waiting on the parked read.
    private static final Duration STOP_BLOCK_OBSERVATION = Duration.ofMillis(200);

    private final TestAdminAlertService alertService = new TestAdminAlertService();
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
        sseService = new SseServiceImpl(executorProvider,
                                        optionRegistry,
                                        displayableRegistry,
                                        SseChannels.factory(clock, alertService),
                                        THROTTLING_PERIOD,
                                        USER_ID,
                                        meterRegistry);
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
        String eventData = dataOf(output, "options-update");
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
        Map<String, Object> parsed = dataOf(capture.output(), "options-update", new TypeToken<>() {});

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
            String eventData = dataOf(output, "options-update");
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
        String eventData = dataOf(output, "options-update");
        assertThat(eventData).contains("\"opt1\"");
        assertThat(eventData).contains("programmatic value");
    }

    // endregion

    // region SSE client lifecycle

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
        when(asyncContext.getRequest()).thenReturn(request);
        lenient().when(asyncContext.getResponse()).thenReturn(response);
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(capture.outputStream()));

        asUnchecked(() -> sseService.startSse(request, response, onStreamClosed));
        clock.tick();

        sseService.stop();
        clock.tick();

        verify(onStreamClosed).run();
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
        when(asyncContext.getRequest()).thenReturn(request);
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
    void stopWaitsForAnOptionSnapshotDeliveryAlreadyReadingTheRegistry(@Mock Option<String> parkingOption,
                                                                       @Mock OptionDto parkingOptionDto) throws InterruptedException {
        try (var race = new TeardownRace(parkingOption, parkingOptionDto)) {
            assertThat(race.arrivals.tryAcquire(PARKED_READ_TIMEOUT.toSeconds(), SECONDS))
                    .as("the queued snapshot delivery has read the registry and is mid-flight")
                    .isTrue();

            assertStopWaitsForTheParkedRead(race);
        }
    }

    @Test
    void stopWaitsForAnInitialImageAlreadyReadingTheRegistry(@Mock Option<String> parkingOption,
                                                             @Mock OptionDto parkingOptionDto) throws InterruptedException {
        try (var race = new TeardownRace(parkingOption, parkingOptionDto)) {
            // Let the subscribe-time delivery run to completion, so the read that parks next is the one a connecting client triggers.
            assertThat(race.arrivals.tryAcquire(PARKED_READ_TIMEOUT.toSeconds(), SECONDS)).isTrue();
            race.departures.release();

            race.connectClient();
            assertThat(race.arrivals.tryAcquire(PARKED_READ_TIMEOUT.toSeconds(), SECONDS))
                    .as("the initial image has read the registry and is mid-flight")
                    .isTrue();

            assertStopWaitsForTheParkedRead(race);
        }
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
        Map<String, Object> parsed = dataOf(capture.output(), "options-update", new TypeToken<>() {});

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
        Map<String, Object> parsed = dataOf(capture.output(), "options-update", new TypeToken<>() {});

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) parsed.get("tabs");
        assertThat(tabs.getFirst().get("id")).isEqualTo("My-Tab-");
    }

    @Test
    void refusesAStreamOnceStoppedAndReleasesTheExchange(@Mock HttpServletRequest request,
                                                         @Mock HttpServletResponse response,
                                                         @Mock AsyncContext asyncContext,
                                                         @Mock Runnable onStreamClosed) {
        when(request.startAsync()).thenReturn(asyncContext);
        sseService.stop();
        clock.tick();

        Closeable handle = getAsUnchecked(() -> sseService.startSse(request, response, onStreamClosed));
        clock.tick();

        // Nobody owns the exchange once the channel is closed, so startSse has to complete it itself and say the stream ended.
        verify(asyncContext).complete();
        verify(onStreamClosed).run();
        assertThatCode(handle::close).doesNotThrowAnyException();
    }

    // endregion

    // region helpers

    private SseCapture connectSseClient() {
        return connectSseClient("localhost", 12345);
    }

    private SseCapture connectSseClient(String host, int port) {
        SseCapture capture = connectClientTo(sseService, host, port);
        clock.tick();
        return capture;
    }

    private static SseCapture connectClientTo(SseServiceImpl service, String host, int port) {
        var capture = new SseCapture();
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var asyncContext = mock(AsyncContext.class);

        when(request.getRemoteHost()).thenReturn(host);
        when(request.getRemotePort()).thenReturn(port);
        when(request.startAsync()).thenReturn(asyncContext);
        when(asyncContext.getRequest()).thenReturn(request);
        lenient().when(asyncContext.getResponse()).thenReturn(response);
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(capture.outputStream()));
        getAsUnchecked(() -> service.startSse(request, response, capture.closedRunnable));
        return capture;
    }

    /// Stops `race`'s service on a thread of its own, asserting that stop waits for the parked read and completes once this releases it.
    private static void assertStopWaitsForTheParkedRead(TeardownRace race) {
        var stopCompletion = CompletableFuture.runAsync(race.service::stop);
        assertThat(stopCompletion).as("stop() waits while the parked read holds the lifecycle lock").failsWithin(STOP_BLOCK_OBSERVATION);

        race.departures.release();
        assertThat(stopCompletion).as("stop() completes once the parked read lets the lock go").succeedsWithin(PARKED_READ_TIMEOUT);
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

    /// Stubs `option` so its [Option#toDto()] — the step [SseServiceImpl] runs right after reading the registry — signals `arrivals` and then parks until a
    /// `departures` permit frees it, holding the read that triggered it inside the SSE service's lifecycle lock.
    private static void stubParkingOnDto(Option<String> option, OptionDto dto, Semaphore arrivals, Semaphore departures) {
        lenient().when(dto.tabName()).thenReturn("Tab");
        when(option.meta()).thenReturn(OptionMeta.<String>builder()
                                                 .setFormOrder(Option.DEFAULT_FORM_ORDER)
                                                 .setTabName("Tab")
                                                 .setKey("parking")
                                                 .setLabel("Parking")
                                                 .build());
        when(option.addChangeListener(any())).thenReturn(Closeable.noop());
        when(option.toDto()).thenAnswer(_ -> {
            arrivals.release();
            assertThat(departures.tryAcquire(PARKED_READ_TIMEOUT.toSeconds(), SECONDS)).as("the parked read was released").isTrue();
            return completedFuture(dto);
        });
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

    private static long countOccurrences(String text, String substring) {
        long count = 0;
        int idx = 0;
        while ((idx = text.indexOf(substring, idx)) >= 0) {
            count++;
            idx += substring.length();
        }
        return count;
    }

    // endregion

    // region test infrastructure

    /// A second [SseServiceImpl] on a real executor, so a read it runs there can be held mid-flight while the test thread plays the part of the thread that
    /// tears the user app down. The option passed to the constructor is what holds it, signalling [#arrivals] as each read reaches it and admitting one
    /// caller per [#departures] permit.
    private final class TeardownRace implements AutoCloseable {
        final Semaphore arrivals = new Semaphore(0);
        final Semaphore departures = new Semaphore(0);
        final SingleThreadedSchedulingExecutor executor = new SingleThreadedSchedulingExecutor("sse-teardown-race");
        final OptionRegistryImpl registry = new OptionRegistryImpl(persistence);
        final SseServiceImpl service = new SseServiceImpl(() -> executor,
                                                          registry,
                                                          displayableRegistry,
                                                          SseChannels.factory(clock, alertService),
                                                          THROTTLING_PERIOD,
                                                          USER_ID,
                                                          meterRegistry);

        TeardownRace(Option<String> parkingOption, OptionDto parkingOptionDto) {
            stubParkingOnDto(parkingOption, parkingOptionDto, arrivals, departures);
            registry.start();
            // Registered before the service subscribes, so the image the subscription delivers is the one and only snapshot read queued by start-up.
            registry.register(parkingOption);
            service.start();
        }

        void connectClient() {
            connectClientTo(service, "localhost", 54321);
        }

        @Override
        public void close() {
            // The executor is single-threaded, so at most one read is ever parked, however the test ended.
            departures.release();
            service.stop();
            registry.stop();
            executor.close();
        }
    }

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
        final Runnable closedRunnable = () -> {};
        private final CapturingServletOutputStream out = new CapturingServletOutputStream();

        String output() {
            return out.output();
        }

        void reset() {
            out.reset();
        }

        ServletOutputStream outputStream() {
            return out;
        }
    }

    // endregion
}
