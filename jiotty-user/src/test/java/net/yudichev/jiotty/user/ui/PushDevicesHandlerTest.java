package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.user.push.PushDeviceRecord;
import net.yudichev.jiotty.user.push.PushDeviceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushDevicesHandlerTest {

    private ProgrammableClock clock;
    @Mock
    private PushDeviceStore pushDeviceStore;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private AsyncContext asyncContext;

    private PushDevicesHandler handler;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        Provider<SchedulingExecutor> executorProvider =
                () -> clock.createSingleThreadedSchedulingExecutor("test");
        handler = new PushDevicesHandler(pushDeviceStore, clock, executorProvider);
        handler.start();
        clock.tick();
    }

    @AfterEach
    void tearDown() {
        handler.stop();
        clock.tick();
    }

    @Test
    void registerUpsertsDeviceAndReturns204(@Captor ArgumentCaptor<PushDeviceRecord> captor) {
        when(pushDeviceStore.upsert(any())).thenReturn(completedFuture(null));
        configureRequest("POST", "/push/devices");
        when(request.startAsync()).thenReturn(asyncContext);
        asUnchecked(() -> when(request.getReader()).thenReturn(
                new BufferedReader(new StringReader("{\"deviceId\":\"dev1\",\"token\":\"tok1\"}"))));

        handler.handle(request, response);
        clock.tick();

        verify(pushDeviceStore).upsert(captor.capture());
        var record = captor.getValue();
        assertThat(record.deviceId()).isEqualTo("dev1");
        assertThat(record.token()).isEqualTo("tok1");
        assertThat(record.registeredAt()).isEqualTo(clock.currentInstant());
        assertThat(record.platform()).isEmpty();
        assertThat(record.appVersion()).isEmpty();
        verify(response).setStatus(204);
        verify(asyncContext).complete();
    }

    @Test
    void registerIncludesOptionalFields(@Captor ArgumentCaptor<PushDeviceRecord> captor) {
        when(pushDeviceStore.upsert(any())).thenReturn(completedFuture(null));
        configureRequest("POST", "/push/devices");
        when(request.startAsync()).thenReturn(asyncContext);
        asUnchecked(() -> when(request.getReader()).thenReturn(
                new BufferedReader(new StringReader(
                        "{\"deviceId\":\"dev1\",\"token\":\"tok1\",\"platform\":\"ios\",\"appVersion\":\"2.1.0\"}"))));

        handler.handle(request, response);
        clock.tick();

        verify(pushDeviceStore).upsert(captor.capture());
        var record = captor.getValue();
        assertThat(record.platform()).hasValue("ios");
        assertThat(record.appVersion()).hasValue("2.1.0");
    }

    @Test
    void registerReturns400ForInvalidJson() {
        var writer = new StringWriter();
        configureRequest("POST", "/push/devices");
        when(request.startAsync()).thenReturn(asyncContext);
        asUnchecked(() -> when(request.getReader()).thenReturn(new BufferedReader(new StringReader("not json"))));
        asUnchecked(() -> when(response.getWriter()).thenReturn(new PrintWriter(writer)));

        handler.handle(request, response);

        verify(response).setStatus(400);
        verify(asyncContext).complete();
        assertThat(parseJson(writer.toString())).extractingByKey("error").asString().contains("Invalid JSON body");
    }

    @Test
    void registerReturns500WhenUpsertFails() {
        when(pushDeviceStore.upsert(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("store down")));
        var writer = new StringWriter();
        configureRequest("POST", "/push/devices");
        when(request.startAsync()).thenReturn(asyncContext);
        asUnchecked(() -> when(request.getReader()).thenReturn(
                new BufferedReader(new StringReader("{\"deviceId\":\"dev1\",\"token\":\"tok1\"}"))));
        asUnchecked(() -> when(response.getWriter()).thenReturn(new PrintWriter(writer)));

        handler.handle(request, response);
        clock.tick();

        verify(response).setStatus(500);
        verify(asyncContext).complete();
        assertThat(parseJson(writer.toString())).extractingByKey("error").asString().contains("store down");
    }

    @Test
    void unregisterRemovesDeviceAndReturns204() {
        when(pushDeviceStore.remove("dev1")).thenReturn(completedFuture(null));
        configureRequest("DELETE", "/push/devices/dev1");
        when(request.startAsync()).thenReturn(asyncContext);

        handler.handle(request, response);
        clock.tick();

        verify(pushDeviceStore).remove("dev1");
        verify(response).setStatus(204);
        verify(asyncContext).complete();
    }

    @Test
    void unregisterReturns500WhenRemoveFails() {
        when(pushDeviceStore.remove("dev1")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("store down")));
        var writer = new StringWriter();
        configureRequest("DELETE", "/push/devices/dev1");
        when(request.startAsync()).thenReturn(asyncContext);
        asUnchecked(() -> when(response.getWriter()).thenReturn(new PrintWriter(writer)));

        handler.handle(request, response);
        clock.tick();

        verify(response).setStatus(500);
        verify(asyncContext).complete();
        assertThat(parseJson(writer.toString())).extractingByKey("error").asString().contains("store down");
    }

    private void configureRequest(String method, String pathInfo) {
        lenient().when(request.getMethod()).thenReturn(method);
        lenient().when(request.getPathInfo()).thenReturn(pathInfo);
    }

    private static Map<String, Object> parseJson(String json) {
        return getAsUnchecked(() -> UIJson.MAPPER.readValue(json, new TypeReference<>() {}));
    }
}
