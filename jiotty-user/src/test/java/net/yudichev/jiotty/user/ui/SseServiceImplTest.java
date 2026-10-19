package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertSeverity;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SingleThreadedSchedulingExecutor;
import net.yudichev.jiotty.common.async.TaskExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.io.testing.FakeServletInputStream;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionDto;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import net.yudichev.jiotty.user.ui.options.TextOption;
import net.yudichev.jiotty.user.ui.sse.SseChannel;
import net.yudichev.jiotty.user.ui.sse.SseInternals;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import net.yudichev.jiotty.user.ui.sse.testing.SseChannels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.user.ui.sse.testing.SseFrames.allDataOf;
import static net.yudichev.jiotty.user.ui.sse.testing.SseFrames.dataOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private static final int MAX_STREAMS = 2;
    private static final String LOCAL_HOST = "localhost";
    private static final int LOCAL_PORT = 12345;
    /// Deadlock safety net for a read parked on another thread, long enough that a loaded machine never trips it.
    private static final Duration PARKED_READ_TIMEOUT = Duration.ofSeconds(5);
    /// How long a stop is watched before concluding it is waiting on the parked read.
    private static final Duration STOP_BLOCK_OBSERVATION = Duration.ofMillis(200);
    private static final HistoryDisplayableDto EMPTY_HISTORY = createHistory("key");

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
        sseService = newService(executorProvider, optionRegistry, MAX_STREAMS);
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
        displayableRegistry.register(createDisplayable("d1", "Display 1", completedFuture(EMPTY_HISTORY)));
        clock.tick();

        SseCapture capture = connectSseClient();

        String output = capture.output();
        assertThat(output).contains("event: hello");
        assertThat(output).contains("event: " + SseServiceImpl.DISPLAYABLE_UPDATE_EVENT);
        assertThat(output).contains("\"id\":\"d1\"");
    }

    @Test
    void connectingSseClient_recordsPostFlushDisplayablesAndOptionsSnapshotTimers() {
        displayableRegistry.register(createDisplayable("d1", "Display 1", completedFuture(EMPTY_HISTORY)));
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
        var displayable = createUpdatingDisplayable("d1", updateTrigger, new MutableReference<>(EMPTY_HISTORY));

        SseCapture capture1 = connectSseClient("host1", 1111);
        SseCapture capture2 = connectSseClient("host2", 2222);
        displayableRegistry.register(displayable);
        clock.tick();

        capture1.reset();
        capture2.reset();

        updateTrigger.get().run();
        clock.tick();
        clock.advanceTimeAndTick(DisplayableRegistryImpl.UPDATE_THROTTLE);

        assertThat(capture1.output()).contains("event: " + SseServiceImpl.DISPLAYABLE_UPDATE_EVENT);
        assertThat(capture2.output()).contains("event: " + SseServiceImpl.DISPLAYABLE_UPDATE_EVENT);
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
            return completedFuture(EMPTY_HISTORY);
        });

        displayableRegistry.register(displayable);
        clock.tick();

        SseCapture capture = connectSseClient();
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

        clock.advanceTimeAndTick(DisplayableRegistryImpl.UPDATE_THROTTLE);
        int afterThrottleCount = callCount[0] - baseCount;
        assertThat(afterThrottleCount).isEqualTo(2);
    }

    @Test
    void displayableUnregistrationStopsUpdates() {
        var displayable = createDisplayable("d1", "Display 1", completedFuture(EMPTY_HISTORY));
        when(displayable.supportsData()).thenReturn(true);
        when(displayable.subscribeForUpdates(any())).thenReturn(() -> {});

        Closeable registration = displayableRegistry.register(displayable);
        clock.tick();

        SseCapture capture = connectSseClient();
        capture.reset();

        registration.close();
        clock.tick();

        String output = capture.output();
        assertThat(output).doesNotContain("event: " + SseServiceImpl.DISPLAYABLE_UPDATE_EVENT);
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
        assertThat(output).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT).contains("\"tabs\"").contains("\"opt1\"").doesNotContain("\"opt2\"");
        capture.reset();

        assertThat(capture.output()).doesNotContain("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);

        clock.advanceTimeAndTick(THROTTLING_PERIOD.dividedBy(2));
        assertThat(capture.output()).doesNotContain("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);

        clock.advanceTimeAndTick(THROTTLING_PERIOD.dividedBy(2));
        output = capture.output();
        assertThat(output).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT).contains("\"tabs\"").contains("\"opt1\"").contains("\"opt2\"");
    }

    @Test
    void burstOptionRegistrationsProduceTwoSseEvents() {
        SseCapture capture = connectSseClient();
        // Drain the startup-image throttle window so the first registration below starts the next throttle cycle fresh.
        clock.advanceTimeAndTick(THROTTLING_PERIOD);
        capture.reset();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD.dividedBy(10));
        assertThat(countOccurrences(capture.output(), "event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT)).isEqualTo(1);
        capture.reset();
        registerTestOption("tab1", "opt2", "Option 2");
        clock.advanceTimeAndTick(THROTTLING_PERIOD.dividedBy(10));
        registerTestOption("tab2", "opt3", "Option 3");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        String output = capture.output();
        assertThat(countOccurrences(output, "event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT)).isEqualTo(1);

        assertThat(output).contains("\"opt1\"");
        assertThat(output).contains("\"opt2\"");
        assertThat(output).contains("\"opt3\"");
    }

    @Test
    void optionUnregistrationBroadcastsUpdatedList() {
        Closeable reg1 = registerTestOption("tab1", "opt1", "Option 1");
        registerTestOption("tab1", "opt2", "Option 2");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        SseCapture capture = connectSseClient();
        capture.reset();

        reg1.close();
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        String output = capture.output();
        assertThat(output).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
        String eventData = dataOf(output, SseServiceImpl.OPTIONS_UPDATE_EVENT);
        assertThat(eventData).doesNotContain("\"opt1\"");
        assertThat(eventData).contains("\"opt2\"");
    }

    @Test
    void sseConnectDeliversInitialOptionsImage() {
        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        SseCapture capture = connectSseClient();

        String output = capture.output();
        assertThat(output).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
        assertThat(output).contains("\"opt1\"");
    }

    @Test
    void optionsUpdateJsonStructure() {
        registerTestOption("MyTab", "my.key", "My Label");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        SseCapture capture = connectSseClient();
        Map<String, Object> optionsUpdate = dataOf(capture.output(), SseServiceImpl.OPTIONS_UPDATE_EVENT, new TypeToken<>() {});

        List<Map<String, Object>> tabs = objectsOf(optionsUpdate, "tabs");
        assertThat(tabs).hasSize(1);
        assertThat(tabs.getFirst().get("name")).isEqualTo("MyTab");
        assertThat(tabs.getFirst().get("id")).isEqualTo("MyTab");

        List<Map<String, Object>> options = objectsOf(tabs.getFirst(), "options");
        assertThat(options).hasSize(1);
        assertThat(options.getFirst().get("key")).isEqualTo("my.key");
        assertThat(options.getFirst().get("label")).isEqualTo("My Label");
        assertThat(options.getFirst().get("type")).isEqualTo("text");
    }

    @Test
    void optionFormPostBroadcastsOptionUpdateViaSse() {
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("post");
        OptionsPostHandler handler = new OptionsPostHandler(optionRegistry, alertService, executorProvider, "user-1");
        handler.start();
        clock.tick();
        try {
            var option = createTestOption("tab1", "opt1", "Option 1");
            optionRegistry.register(option);
            clock.advanceTimeAndTick(THROTTLING_PERIOD);

            SseCapture capture = connectSseClient();
            capture.reset();

            var request = mock(HttpServletRequest.class);
            var response = mock(HttpServletResponse.class);
            when(request.getMethod()).thenReturn("POST");
            when(request.startAsync()).thenReturn(new FakeAsyncContext(request, response));
            String form = OptionsPostHandler.NAME_FIELD + "=opt1&" + OptionsPostHandler.VALUE_FIELD + "=new+value";
            when(request.getContentLengthLong()).thenReturn((long) form.length());
            asUnchecked(() -> when(request.getInputStream()).thenReturn(FakeServletInputStream.of(form)));
            // This test asserts on the SSE broadcast, not the POST's own body, so the response only has to accept the bytes.
            asUnchecked(() -> when(response.getOutputStream()).thenReturn(new CapturingServletOutputStream()));

            handler.handle(request, response);
            clock.advanceTimeAndTick(THROTTLING_PERIOD);

            String output = capture.output();
            assertThat(output).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
            String eventData = dataOf(output, SseServiceImpl.OPTIONS_UPDATE_EVENT);
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

        SseCapture capture = connectSseClient();
        capture.reset();

        option.setValue("programmatic value");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        String output = capture.output();
        assertThat(output).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
        String eventData = dataOf(output, SseServiceImpl.OPTIONS_UPDATE_EVENT);
        assertThat(eventData).contains("\"opt1\"");
        assertThat(eventData).contains("programmatic value");
    }

    // endregion

    // region SSE client lifecycle

    @Test
    void doStopClosesSseClients() {
        SseCapture capture = connectSseClient();

        sseService.stop();
        clock.tick();

        verify(capture.onStreamClosed).run();
    }

    @Test
    void startSseInitialImageDrainedAfterStopClosesClientWithoutReadingRegistries() {
        var capture = new SseCapture(LOCAL_HOST, LOCAL_PORT);

        // Enqueue the initial-image task but do not tick, so it stays queued on the executor.
        capture.startOn(sseService);

        // Tear down in reverse dependency order — the SSE service first, then the registries it reads — mirroring per-user injector teardown, and only now
        // tick, so the queued initial-image task drains after every component has stopped and must close the new client without reading the registries.
        sseService.stop();
        displayableRegistry.stop();
        optionRegistry.stop();
        clock.tick();

        // A stream opened on a service that stopped before its initial image ran is sent no snapshot, and is closed.
        assertThat(capture.output()).doesNotContain("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT)
                                    .doesNotContain("event: " + SseServiceImpl.DISPLAYABLE_UPDATE_EVENT);
        verify(capture.onStreamClosed).run();
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
        SseCapture capture1 = connectSseClient("host1", 1111);
        SseCapture capture2 = connectSseClient("host2", 2222);
        capture1.reset();
        capture2.reset();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        assertThat(capture1.output()).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
        assertThat(capture2.output()).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
    }

    @Test
    void newClientConnectionDoesNotRebroadcastDisplayablesToExistingClients() {
        displayableRegistry.register(createDisplayable("d1", "Display 1", completedFuture(EMPTY_HISTORY)));
        clock.tick();

        SseCapture capture1 = connectSseClient("host1", 1111);
        assertThat(capture1.output()).contains("event: " + SseServiceImpl.DISPLAYABLE_UPDATE_EVENT);
        capture1.reset();

        connectSseClient("host2", 2222);

        assertThat(capture1.output()).doesNotContain("event: " + SseServiceImpl.DISPLAYABLE_UPDATE_EVENT);
    }

    @Test
    void newClientConnectionDoesNotRebroadcastOptionsToExistingClients() {
        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        SseCapture capture1 = connectSseClient("host1", 1111);
        assertThat(capture1.output()).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
        capture1.reset();

        connectSseClient("host2", 2222);

        assertThat(capture1.output()).doesNotContain("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
    }

    @Test
    void refusesAStreamPastTheUsersCapAndKeepsTheEarlierStreamsOpen() {
        var admittedCaptures = new ArrayList<SseCapture>(MAX_STREAMS);
        for (int i = 0; i < MAX_STREAMS; i++) {
            admittedCaptures.add(connectSseClient("host" + i, 1000 + i));
        }

        SseCapture refusedCapture = connectSseClient("host-refused", 2000);

        verify(refusedCapture.response).setStatus(SERVICE_UNAVAILABLE_503);
        assertThat(refusedCapture.out.errorCode()).isEqualTo(SseInternals.CHANNEL_FULL_ERROR);
        assertThat(refusedCapture.asyncContext.isCompleted()).isTrue();
        verify(refusedCapture.onStreamClosed).run();
        assertThat(admittedCaptures).noneSatisfy(capture -> assertThat(capture.asyncContext.isCompleted()).isTrue());
    }

    /// A client that reads slowly is sent the latest update of each displayable: an unsent update is superseded by a later one about the same displayable only.
    @Test
    void aSlowClientGetsTheLatestUpdateOfEachDisplayable() {
        var firstTrigger = new MutableReference<Runnable>();
        var firstDto = new MutableReference<DisplayableDto>(EMPTY_HISTORY);
        displayableRegistry.register(createUpdatingDisplayable("d1", firstTrigger, firstDto));
        var secondTrigger = new MutableReference<Runnable>();
        var secondDto = new MutableReference<DisplayableDto>(EMPTY_HISTORY);
        displayableRegistry.register(createUpdatingDisplayable("d2", secondTrigger, secondDto));
        SseCapture capture = connectSseClient();
        capture.stallReading();
        // The d0 frame fills the write the client is not reading, so the updates registered after it stay pending.
        displayableRegistry.register(createDisplayable("d0", "Held", completedFuture(EMPTY_HISTORY)));
        clock.tick();

        firstDto.set(createHistory("d1-superseded"));
        firstTrigger.get().run();
        clock.tick();
        secondDto.set(createHistory("d2-only"));
        secondTrigger.get().run();
        clock.tick();
        firstDto.set(createHistory("d1-latest"));
        firstTrigger.get().run();
        // Past the displayable registry's throttle, which holds back a second update to d1 until then.
        clock.advanceTimeAndTick(DisplayableRegistryImpl.UPDATE_THROTTLE);
        capture.resumeReading();

        assertThat(allDataOf(capture.output(), SseServiceImpl.DISPLAYABLE_UPDATE_EVENT))
                .noneSatisfy(data -> assertThat(data).contains("d1-superseded"))
                .anySatisfy(data -> assertThat(data).contains("d2-only"))
                .anySatisfy(data -> assertThat(data).contains("d1-latest"));
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
        displayableRegistry.register(createDisplayable("d1", "Display 1", completedFuture(EMPTY_HISTORY)));
        clock.tick();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        SseCapture capture = connectSseClient();
        String output = capture.output();

        assertThat(output).contains("event: " + SseServiceImpl.DISPLAYABLE_UPDATE_EVENT);
        assertThat(output).contains("event: " + SseServiceImpl.OPTIONS_UPDATE_EVENT);
    }

    // endregion

    // region edge cases

    @Test
    void emptyOptionsProducesEmptyTabsArray() {
        SseCapture capture = connectSseClient();
        Map<String, Object> optionsUpdate = dataOf(capture.output(), SseServiceImpl.OPTIONS_UPDATE_EVENT, new TypeToken<>() {});

        assertThat(objectsOf(optionsUpdate, "tabs")).isEmpty();
    }

    @Test
    void displayableDtoFailureDoesNotBreakSseStream() {
        displayableRegistry.register(createDisplayable("d1", "Display 1",
                                                       CompletableFuture.failedFuture(new RuntimeException("DTO generation failed"))));
        clock.tick();

        SseCapture capture = connectSseClient();

        capture.reset();
        clock.advanceTimeAndTick(SseInternals.HEARTBEAT_PERIOD);
        assertThat(capture.output()).contains("event: ping");
        // A displayable that cannot render is a defect in this server, and it leaves the user's screen stale, so an operator hears about it.
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.title()).isEqualTo("Displayable failed to generate its DTO");
                    assertThat(alert.severity()).isEqualTo(AdminAlertSeverity.ERROR);
                });
    }

    @Test
    void optionTabIdSanitisesSpecialCharacters() {
        registerTestOption("My Tab!", "opt1", "Label");
        clock.advanceTimeAndTick(THROTTLING_PERIOD);

        SseCapture capture = connectSseClient();
        Map<String, Object> optionsUpdate = dataOf(capture.output(), SseServiceImpl.OPTIONS_UPDATE_EVENT, new TypeToken<>() {});

        assertThat(objectsOf(optionsUpdate, "tabs").getFirst().get("id")).isEqualTo("My-Tab-");
    }

    @Test
    void refusesAStreamOnceStoppedAndReleasesTheExchange() {
        sseService.stop();
        clock.tick();
        var capture = new SseCapture(LOCAL_HOST, LOCAL_PORT);

        Closeable handle = capture.startOn(sseService);
        clock.tick();

        // A closed channel refuses the stream, so startSse writes its 503, completes the exchange and runs onStreamClosed.
        verify(capture.response).setStatus(SERVICE_UNAVAILABLE_503);
        assertThat(capture.asyncContext.isCompleted()).isTrue();
        verify(capture.onStreamClosed).run();
        assertThatCode(handle::close).doesNotThrowAnyException();
    }

    @Test
    void namesTheFaultWhenAskedForAStreamBeforeStarting() {
        var unstartedService = newService(() -> clock.createSingleThreadedSchedulingExecutor("unstarted"), optionRegistry, MAX_STREAMS);
        var capture = new SseCapture(LOCAL_HOST, LOCAL_PORT);

        assertThatThrownBy(() -> capture.startOn(unstartedService))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before it started");
    }

    @Test
    void refusesANonPositiveStreamCap() {
        assertThatThrownBy(() -> newService(() -> clock.createSingleThreadedSchedulingExecutor("capless"), optionRegistry, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // endregion

    // region helpers

    private SseServiceImpl newService(Provider<SchedulingExecutor> executorProvider, OptionRegistry serviceOptionRegistry, int maxStreams) {
        return new SseServiceImpl(executorProvider,
                                  serviceOptionRegistry,
                                  displayableRegistry,
                                  SseChannels.createFactory(clock, alertService),
                                  alertService,
                                  THROTTLING_PERIOD,
                                  USER_ID,
                                  maxStreams,
                                  meterRegistry);
    }

    private SseCapture connectSseClient() {
        return connectSseClient(LOCAL_HOST, LOCAL_PORT);
    }

    private SseCapture connectSseClient(String host, int port) {
        var capture = new SseCapture(host, port);
        capture.startOn(sseService);
        clock.tick();
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

    /// A displayable whose updates `trigger` fires, rendering what `dto` holds at the time.
    private static Displayable createUpdatingDisplayable(String id, MutableReference<Runnable> trigger, MutableReference<DisplayableDto> dto) {
        var displayable = createDisplayable(id, id, null);
        when(displayable.supportsData()).thenReturn(true);
        when(displayable.subscribeForUpdates(any())).thenAnswer(invocation -> {
            trigger.set(invocation.getArgument(0));
            return (Closeable) () -> {};
        });
        when(displayable.toDto()).thenAnswer(_ -> completedFuture(dto.get()));
        return displayable;
    }

    /// A history of one empty group, named `group` so a test can tell the frames rendering it apart.
    private static HistoryDisplayableDto createHistory(String group) {
        return new HistoryDisplayableDto(ImmutableMap.of(group, ImmutableList.of()));
    }

    /// Returns `node`'s `field`, a JSON array of objects.
    @SuppressWarnings("unchecked") // Jackson maps a JSON array of objects to a List of Maps
    private static List<Map<String, Object>> objectsOf(Map<String, Object> node, String field) {
        return (List<Map<String, Object>>) node.get(field);
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
        final SseServiceImpl service = newService(() -> executor, registry, MAX_STREAMS);

        TeardownRace(Option<String> parkingOption, OptionDto parkingOptionDto) {
            stubParkingOnDto(parkingOption, parkingOptionDto, arrivals, departures);
            registry.start();
            // Registered before the service subscribes, so the image the subscription delivers is the one and only snapshot read queued by start-up.
            registry.register(parkingOption);
            service.start();
        }

        void connectClient() {
            new SseCapture(LOCAL_HOST, LOCAL_PORT).startOn(service);
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

    /// One client's exchange and the bytes the server pushed down it.
    private static final class SseCapture {
        final HttpServletResponse response = mock(HttpServletResponse.class);
        final Runnable onStreamClosed = mock(Runnable.class);
        final FakeAsyncContext asyncContext;
        private final HttpServletRequest request = mock(HttpServletRequest.class);
        private final CapturingServletOutputStream out = new CapturingServletOutputStream();

        SseCapture(String host, int port) {
            asyncContext = new FakeAsyncContext(request, response);
            // Lenient, because a refused or faulted stream reads none of these.
            lenient().when(request.getRemoteHost()).thenReturn(checkNotNull(host, "host"));
            lenient().when(request.getRemotePort()).thenReturn(port);
            lenient().when(request.startAsync()).thenReturn(asyncContext);
            asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(out));
        }

        Closeable startOn(SseServiceImpl service) {
            return service.startSse(request, response, onStreamClosed);
        }

        String output() {
            return out.output();
        }

        void reset() {
            out.reset();
        }

        void stallReading() {
            out.stallReading();
        }

        void resumeReading() {
            out.resumeReading();
        }
    }

    // endregion
}
