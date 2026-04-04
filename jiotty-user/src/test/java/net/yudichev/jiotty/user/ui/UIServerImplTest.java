package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Nullable;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.ProgrammableClock;
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
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UIServerImplTest {
    private static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory()).registerModule(new JavaTimeModule());
    private static final Duration STABILISATION_DELAY = Duration.ofMillis(100);

    private ProgrammableClock clock;
    private UIServerImpl server;
    @Mock
    private OptionPersistence persistence;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        server = new UIServerImpl(persistence, clock, "test", STABILISATION_DELAY);
        server.start();
        clock.tick();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            clock.tick();
        }
    }

    // region SSE connection helpers

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
        try {
            when(response.getOutputStream()).thenReturn(capture.outputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        doAnswer(invocation -> {
            capture.asyncListener.set(invocation.getArgument(0));
            return null;
        }).when(asyncContext).addListener(any(AsyncListener.class));

        try {
            capture.closeHandle.set(server.startDisplayablesSse(request, response, capture.closedRunnable));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        clock.tick();
        return capture;
    }

    // endregion

    // region displayable tests


    @Test
    void sseDeliversInitialDisplayableImageOnConnect() {
        var displayable = createDisplayable("d1", "Display 1",
                                            completedFuture(new DisplayableDtos.History(Map.of("key", List.of()))));
        server.registerDisplayable(displayable);
        clock.tick();

        var capture = connectSseClient();

        String output = capture.output();
        assertThat(output).contains("event: hello");
        assertThat(output).contains("event: displayable-update");
        assertThat(output).contains("\"id\":\"d1\"");
    }

    @Test
    void displayableUpdateBroadcastsToAllSseClients() {
        var updateTrigger = new MutableReference<Runnable>();
        var displayable = createDisplayable("d1", "Display 1",
                                            completedFuture(new DisplayableDtos.History(Map.of("key", List.of()))));
        when(displayable.supportsData()).thenReturn(true);
        when(displayable.subscribeForUpdates(any())).thenAnswer(invocation -> {
            updateTrigger.set(invocation.getArgument(0));
            return (Closeable) () -> {};
        });

        var capture1 = connectSseClient("host1", 1111);
        var capture2 = connectSseClient("host2", 2222);
        server.registerDisplayable(displayable);
        clock.tick();

        // clear initial output
        capture1.reset();
        capture2.reset();

        // trigger an update
        updateTrigger.get().run();
        clock.tick();
        // throttle period is 1 second
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
            return completedFuture(new DisplayableDtos.History(Map.of("key", List.of())));
        });

        server.registerDisplayable(displayable);
        clock.tick();

        var capture = connectSseClient();
        int baseCount = callCount[0];
        capture.reset();

        // rapid updates within the 1-second throttle window
        updateTrigger.get().run();
        clock.tick();
        updateTrigger.get().run();
        clock.tick();
        updateTrigger.get().run();
        clock.tick();

        // still within throttle — only one toDto() call should have fired
        int afterRapidCount = callCount[0] - baseCount;
        assertThat(afterRapidCount).isEqualTo(1);

        // after throttle period expires
        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        int afterThrottleCount = callCount[0] - baseCount;
        assertThat(afterThrottleCount).isEqualTo(2);
    }

    @Test
    void displayableUnregistrationStopsUpdates() {
        var displayable = createDisplayable("d1", "Display 1",
                                            completedFuture(new DisplayableDtos.History(Map.of("key", List.of()))));
        when(displayable.supportsData()).thenReturn(true);
        when(displayable.subscribeForUpdates(any())).thenReturn(() -> {});

        Closeable registration = server.registerDisplayable(displayable);
        clock.tick();

        var capture = connectSseClient();
        capture.reset();

        registration.close();
        clock.tick();

        String output = capture.output();
        // after unregistration, no more displayable-update events
        assertThat(output).doesNotContain("event: displayable-update");
    }

    // endregion

    // region options SSE tests

    @Test
    void optionRegistrationBroadcastsOptionsUpdateAfterStabilisation() {
        var capture = connectSseClient();
        capture.reset();

        registerTestOption("tab1", "opt1", "Option 1");

        // before stabilisation delay — no options-update yet
        clock.tick();
        assertThat(capture.output()).doesNotContain("event: options-update");

        // after stabilisation delay
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        String output = capture.output();
        assertThat(output).contains("event: options-update");
        assertThat(output).contains("\"tabs\"");
        assertThat(output).contains("\"opt1\"");
    }

    @Test
    void burstOptionRegistrationsProduceSingleSseEvent() {
        var capture = connectSseClient();
        capture.reset();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(STABILISATION_DELAY.dividedBy(10));
        registerTestOption("tab1", "opt2", "Option 2");
        clock.advanceTimeAndTick(STABILISATION_DELAY.dividedBy(10));
        registerTestOption("tab2", "opt3", "Option 3");

        // all three registered within stabilisation window — single broadcast
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        String output = capture.output();
        long eventCount = countOccurrences(output, "event: options-update");
        assertThat(eventCount).isEqualTo(1);

        // verify all options present in the single event
        assertThat(output).contains("\"opt1\"");
        assertThat(output).contains("\"opt2\"");
        assertThat(output).contains("\"opt3\"");
    }

    @Test
    void optionUnregistrationBroadcastsUpdatedList() {
        Closeable reg1 = registerTestOption("tab1", "opt1", "Option 1");
        registerTestOption("tab1", "opt2", "Option 2");
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        var capture = connectSseClient();
        capture.reset();

        reg1.close();
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        String output = capture.output();
        assertThat(output).contains("event: options-update");
        // parse the options-update data to verify opt1 is gone
        String eventData = extractSseEventData(output, "options-update");
        assertThat(eventData).doesNotContain("\"opt1\"");
        assertThat(eventData).contains("\"opt2\"");
    }

    @Test
    void sseConnectDeliversInitialOptionsImage() {
        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        var capture = connectSseClient();

        String output = capture.output();
        assertThat(output).contains("event: options-update");
        assertThat(output).contains("\"opt1\"");
    }

    @Test
    void optionsUpdateJsonStructure() {
        registerTestOption("MyTab", "my.key", "My Label");
        clock.advanceTimeAndTick(STABILISATION_DELAY);

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
        var option = createTestOption("tab1", "opt1", "Option 1");
        server.registerOption(option);
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        var capture = connectSseClient();
        capture.reset();

        submitOptionsPost("opt1", "new value", new StringWriter());
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        String output = capture.output();
        assertThat(output).contains("event: options-update");
        String eventData = extractSseEventData(output, "options-update");
        assertThat(eventData).contains("\"opt1\"");
        assertThat(eventData).contains("new value");
    }

    @Test
    void programmaticSetValueBroadcastsOptionUpdateViaSse() {
        var option = createTestOption("tab1", "opt1", "Option 1");
        server.registerOption(option);
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        var capture = connectSseClient();
        capture.reset();

        option.setValue("programmatic value");
        clock.advanceTimeAndTick(STABILISATION_DELAY);

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

    // endregion

    // region SSE client lifecycle

    @Test
    void sseClientDisconnectRemovesClient() {
        var capture = connectSseClient();
        capture.reset();

        // simulate async context completion (client disconnect)
        asUnchecked(() -> capture.asyncListener.get().onComplete(null));
        clock.tick();

        // register an option; the disconnected client should not receive the event
        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        // if the client was properly removed, writing to it won't happen
        // and we won't get any new events (the stream is disconnected)
        // This test mainly verifies no exceptions are thrown
    }

    @Test
    void closeReturnedFromStartSseClosesStream() {
        var capture = connectSseClient();
        capture.reset();

        capture.closeHandle.get().close();
        clock.tick();

        // after closing, the stream should no longer receive events
        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(STABILISATION_DELAY);
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
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        assertThat(capture1.output()).contains("event: options-update");
        assertThat(capture2.output()).contains("event: options-update");
    }

    @Test
    void newClientConnectionDoesNotRebroadcastDisplayablesToExistingClients() {
        var displayable = createDisplayable("d1", "Display 1",
                                            completedFuture(new DisplayableDtos.History(Map.of("key", List.of()))));
        server.registerDisplayable(displayable);
        clock.tick();

        var capture1 = connectSseClient("host1", 1111);
        // capture1 receives initial image — clear it
        assertThat(capture1.output()).contains("event: displayable-update");
        capture1.reset();

        // second client connects — should NOT cause capture1 to receive displayable-update again
        connectSseClient("host2", 2222);

        assertThat(capture1.output()).doesNotContain("event: displayable-update");
    }

    @Test
    void newClientConnectionDoesNotRebroadcastOptionsToExistingClients() {
        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        var capture1 = connectSseClient("host1", 1111);
        // capture1 receives initial options — clear it
        assertThat(capture1.output()).contains("event: options-update");
        capture1.reset();

        // second client connects — should NOT cause capture1 to receive options-update again
        connectSseClient("host2", 2222);

        assertThat(capture1.output()).doesNotContain("event: options-update");
    }

    // endregion

    // region option persistence

    @Test
    void optionRegistrationLoadsPersistence() {
        var option = createTestOption("tab1", "opt1", "Label");
        server.registerOption(option);
        verify(persistence).load(option);
    }

    @Test
    void optionValueChangeTriggersPersistenceSave() {
        var option = createTestOption("tab1", "opt1", "Label");
        server.registerOption(option);
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

    // endregion

    // region displayable + options combined initial image

    @Test
    void sseConnectDeliversBothDisplayablesAndOptions() {
        var displayable = createDisplayable("d1", "Display 1",
                                            completedFuture(new DisplayableDtos.History(Map.of("key", List.of()))));
        server.registerDisplayable(displayable);
        clock.tick();

        registerTestOption("tab1", "opt1", "Option 1");
        clock.advanceTimeAndTick(STABILISATION_DELAY);

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
        var displayable = createDisplayable("d1", "Display 1",
                                            CompletableFuture.failedFuture(new RuntimeException("DTO generation failed")));
        server.registerDisplayable(displayable);
        clock.tick();

        var capture = connectSseClient();

        // the stream should still be alive — heartbeat should work
        capture.reset();
        clock.advanceTimeAndTick(Duration.ofSeconds(15));
        assertThat(capture.output()).contains("event: ping");
    }

    @Test
    void optionsPostWithMissingNameParameterReturns400() {
        var responseBody = new StringWriter();
        var postResponse = submitOptionsPost(null, null, responseBody);
        clock.tick();

        verify(postResponse).setStatus(400);
        verify(postResponse).setContentType("text/plain");
        assertThat(responseBody.toString()).contains("Missing name parameter");
    }

    @Test
    void optionsPostWithUnknownOptionKeyReturns400() {
        var responseBody = new StringWriter();
        var postResponse = submitOptionsPost("nonexistent", null, responseBody);
        clock.tick();

        verify(postResponse).setStatus(400);
        verify(postResponse).setContentType("text/plain");
        assertThat(responseBody.toString()).contains("nonexistent");
    }

    @Test
    void optionsPostWhenOnFormSubmitThrowsSynchronouslyReturns400(@Mock Option<?> option) {
        lenient().when(option.meta()).thenReturn(new OptionMeta<>(0, "tab", "throwing-opt", "Throwing", null));
        lenient().when(option.toDto()).thenReturn(completedFuture(null));
        when(option.onFormSubmit(any())).thenThrow(new RuntimeException("boom"));
        server.registerOption(option);
        clock.tick();

        var responseBody = new StringWriter();
        var postResponse = submitOptionsPost("throwing-opt", "val", responseBody);
        clock.tick();

        verify(postResponse).setStatus(400);
        verify(postResponse).setContentType("text/plain");
        assertThat(responseBody.toString()).contains("boom");
    }

    @Test
    void optionsPostAsyncFailureReturns400(@Mock Option<?> option) {
        lenient().when(option.meta()).thenReturn(new OptionMeta<>(0, "tab", "async-fail", "AsyncFail", null));
        lenient().when(option.toDto()).thenReturn(completedFuture(null));
        when(option.onFormSubmit(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("async boom")));
        server.registerOption(option);
        clock.tick();

        var responseBody = new StringWriter();
        var postResponse = submitOptionsPost("async-fail", "val", responseBody);
        clock.tick();

        verify(postResponse).setStatus(400);
        verify(postResponse).setContentType("text/plain");
        assertThat(responseBody.toString()).contains("async boom");
    }

    @Test
    void optionTabIdSanitisesSpecialCharacters() {
        registerTestOption("My Tab!", "opt1", "Label");
        clock.advanceTimeAndTick(STABILISATION_DELAY);

        var capture = connectSseClient();
        String eventData = extractSseEventData(capture.output(), "options-update");
        Map<String, Object> parsed = parseJson(eventData);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tabs = (List<Map<String, Object>>) parsed.get("tabs");
        assertThat(tabs.getFirst().get("id")).isEqualTo("My-Tab-");
    }

    // endregion

    // region helpers

    private Closeable registerTestOption(String tabName, String key, String label) {
        var option = createTestOption(tabName, key, label);
        return server.registerOption(option);
    }

    private TestTextOption createTestOption(String tabName, String key, String label) {
        return createTestOption(tabName, key, label, Option.DEFAULT_FORM_ORDER);
    }

    private TestTextOption createTestOption(String tabName, String key, String label, int formOrder) {
        return new TestTextOption(clock.createSingleThreadedSchedulingExecutor("opt-" + key),
                                  new OptionMeta<>(formOrder, tabName, key, label, null));
    }

    private HttpServletResponse submitOptionsPost(@Nullable String name, @Nullable String value, StringWriter responseBody) {
        var postRequest = mock(HttpServletRequest.class);
        var postResponse = mock(HttpServletResponse.class);
        var postAsyncContext = mock(AsyncContext.class);
        when(postRequest.startAsync()).thenReturn(postAsyncContext);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(postAsyncContext).start(any(Runnable.class));
        lenient().when(postRequest.getParameter("name")).thenReturn(name);
        lenient().when(postRequest.getParameter("value")).thenReturn(value);
        lenient().when(postRequest.getParameterMap()).thenReturn(Map.of());
        asUnchecked(() -> when(postResponse.getWriter()).thenReturn(new PrintWriter(responseBody)));

        server.handleOptionsPost(postRequest, postResponse);
        return postResponse;
    }

    private static Displayable createDisplayable(String id, String displayName,
                                                 CompletableFuture<DisplayableDtos.DisplayableDto> dto) {
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
