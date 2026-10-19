package net.yudichev.jiotty.user.ui;

import jakarta.inject.Provider;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertSeverity;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.user.push.PushDeviceRecord;
import net.yudichev.jiotty.user.push.PushDeviceStore;
import net.yudichev.jiotty.user.ui.io.testing.DispatchedRequest;
import net.yudichev.jiotty.user.ui.io.testing.FailingServletInputStream;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.io.testing.FakeServletInputStream;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;
import static net.yudichev.jiotty.common.rest.HttpStatuses.PAYLOAD_TOO_LARGE_413;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushDevicesHandlerTest {
    private final CapturingServletOutputStream responseOutput = new CapturingServletOutputStream();
    private final List<DispatchedRequest> dispatchedRequests = new ArrayList<>(1);
    private ProgrammableClock clock;
    @Mock
    private PushDeviceStore pushDeviceStore;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    private FakeAsyncContext asyncContext;
    private TestAdminAlertService alertService;
    private PushDevicesHandler handler;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        Provider<SchedulingExecutor> executorProvider =
                () -> clock.createSingleThreadedSchedulingExecutor("test");
        alertService = new TestAdminAlertService();
        handler = new PushDevicesHandler(pushDeviceStore, clock, alertService, executorProvider, "user-1");
        handler.start();
        clock.tick();
        asyncContext = new FakeAsyncContext(request, response);
        lenient().when(request.startAsync()).thenReturn(asyncContext);
        asUnchecked(() -> lenient().when(response.getOutputStream()).thenReturn(responseOutput));
    }

    /// Once the exchange has started, a deadline can complete it and the container recycle the request at any moment, so nothing reads it after dispatch.
    @AfterEach
    void tearDown() {
        handler.stop();
        clock.tick();
        assertThat(responseOutput.leakedBuffers()).isEmpty();
        assertThat(dispatchedRequests).allSatisfy(dispatchedRequest -> assertThat(dispatchedRequest.callsAfterDispatch()).isEmpty());
    }

    @Test
    void registerUpsertsDeviceAndReturns204(@Captor ArgumentCaptor<PushDeviceRecord> captor) {
        when(pushDeviceStore.upsert(any())).thenReturn(completedFuture(null));
        configureRequest("POST", PushDevicesHandler.PATH);
        withBody("{\"deviceId\":\"dev1\",\"token\":\"tok1\"}");

        dispatch();
        clock.tick();

        verify(pushDeviceStore).upsert(captor.capture());
        var record = captor.getValue();
        assertThat(record.deviceId()).isEqualTo("dev1");
        assertThat(record.token()).isEqualTo("tok1");
        assertThat(record.registeredAt()).isEqualTo(clock.currentInstant());
        assertThat(record.platform()).isEmpty();
        assertThat(record.appVersion()).isEmpty();
        verify(response).setStatus(NO_CONTENT_204);
        assertThat(asyncContext.isCompleted()).isTrue();
    }

    @Test
    void registerIncludesOptionalFields(@Captor ArgumentCaptor<PushDeviceRecord> captor) {
        when(pushDeviceStore.upsert(any())).thenReturn(completedFuture(null));
        configureRequest("POST", PushDevicesHandler.PATH);
        withBody("{\"deviceId\":\"dev1\",\"token\":\"tok1\",\"platform\":\"ios\",\"appVersion\":\"2.1.0\"}");

        dispatch();
        clock.tick();

        verify(pushDeviceStore).upsert(captor.capture());
        var record = captor.getValue();
        assertThat(record.platform()).hasValue("ios");
        assertThat(record.appVersion()).hasValue("2.1.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", ""})
    void registerReturns400ForABodyThatIsNotARegistration(String body) {
        configureRequest("POST", PushDevicesHandler.PATH);
        withBody(body);

        dispatch();
        clock.tick();

        verify(response).setStatus(BAD_REQUEST_400);
        assertThat(asyncContext.isCompleted()).isTrue();
        verifyNoInteractions(pushDeviceStore);
        assertThat(responseOutput.errorCode()).isEqualTo(ErrorResponse.INVALID_BODY);
    }

    @Test
    void registerReturns413ForOversizedBody() {
        configureRequest("POST", PushDevicesHandler.PATH);
        withBody("{\"deviceId\":\"" + "x".repeat(40 * 1024) + "\"}");

        dispatch();
        clock.tick();

        verify(response).setStatus(PAYLOAD_TOO_LARGE_413);
        assertThat(asyncContext.isCompleted()).isTrue();
        verifyNoInteractions(pushDeviceStore);
        assertThat(responseOutput.errorCode()).isEqualTo(ErrorResponse.BODY_TOO_LARGE);
    }

    /// A body that cannot be read, as when the client disconnects mid-upload, is answered 400 and raises no admin alert.
    @Test
    void registerReturns400WhenTheBodyCannotBeRead() {
        configureRequest("POST", PushDevicesHandler.PATH);
        when(request.getContentLengthLong()).thenReturn(10L);
        asUnchecked(() -> when(request.getInputStream()).thenReturn(new FailingServletInputStream(new IOException("connection reset"))));

        dispatch();
        clock.tick();

        verify(response).setStatus(BAD_REQUEST_400);
        assertThat(asyncContext.isCompleted()).isTrue();
        verifyNoInteractions(pushDeviceStore);
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    /// A client trickling its body past the deadline is answered with a server fault, and raises no alert: any caller can cause it, so an alert would let
    /// one flood the operator.
    @Test
    void registerAnswersABodyStillArrivingAtTheDeadlineWithoutAnAlert(@Mock ServletInputStream neverReadyInput) {
        configureRequest("POST", PushDevicesHandler.PATH);
        when(request.getContentLengthLong()).thenReturn(10L);
        asUnchecked(() -> when(request.getInputStream()).thenReturn(neverReadyInput));
        dispatch();
        clock.tick();

        asyncContext.expireDeadlines();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(alertService.activeAlertsById()).isEmpty();
        verifyNoInteractions(pushDeviceStore);
    }

    @Test
    void registerReturns500WhenUpsertFails() {
        when(pushDeviceStore.upsert(any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("store down")));
        configureRequest("POST", PushDevicesHandler.PATH);
        withBody("{\"deviceId\":\"dev1\",\"token\":\"tok1\"}");

        dispatch();
        clock.tick();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(asyncContext.isCompleted()).isTrue();
        assertThat(responseOutput.errorCode()).isEqualTo(ErrorResponse.INTERNAL_ERROR);
        assertRaisedAlert();
    }

    /// A store that throws synchronously, as it does while stopping, yields a 500 and raises an admin alert.
    @Test
    void registerReturns500WhenUpsertThrows() {
        when(pushDeviceStore.upsert(any())).thenThrow(new IllegalStateException("store stopping"));
        configureRequest("POST", PushDevicesHandler.PATH);
        withBody("{\"deviceId\":\"dev1\",\"token\":\"tok1\"}");

        dispatch();
        clock.tick();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(asyncContext.isCompleted()).isTrue();
        assertRaisedAlert();
    }

    @Test
    void unregisterRemovesDeviceAndReturns204() {
        when(pushDeviceStore.remove("dev1")).thenReturn(completedFuture(null));
        configureRequest("DELETE", PushDevicesHandler.PATH + "/dev1");

        dispatch();
        clock.tick();

        verify(pushDeviceStore).remove("dev1");
        verify(response).setStatus(NO_CONTENT_204);
        assertThat(asyncContext.isCompleted()).isTrue();
    }

    @Test
    void unregisterReturns500WhenRemoveFails() {
        when(pushDeviceStore.remove("dev1")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("store down")));
        configureRequest("DELETE", PushDevicesHandler.PATH + "/dev1");

        dispatch();
        clock.tick();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(asyncContext.isCompleted()).isTrue();
        assertThat(responseOutput.errorCode()).isEqualTo(ErrorResponse.INTERNAL_ERROR);
        assertRaisedAlert();
    }

    /// A store call that never completes is answered with a server fault once the deadline passes, and alerted.
    @Test
    void unregisterThatNeverCompletesIsAnsweredByTheDeadline() {
        when(pushDeviceStore.remove("dev1")).thenReturn(new CompletableFuture<>());
        configureRequest("DELETE", PushDevicesHandler.PATH + "/dev1");
        dispatch();
        clock.tick();

        asyncContext.expireDeadlines();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> assertThat(alert.title()).isEqualTo(PushDevicesHandler.TIMED_OUT_ALERT_TITLE));
    }

    /// Asserts the one admin alert a store failure raises: a WARNING, because the failure is confined to one request.
    private void dispatch() {
        dispatchedRequests.add(DispatchedRequest.dispatch(request, asyncContext, dispatched -> handler.handle(dispatched, response)));
    }

    private void assertRaisedAlert() {
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.severity()).isEqualTo(AdminAlertSeverity.WARNING);
                    assertThat(alert.title()).isEqualTo(PushDevicesHandler.REQUEST_FAILED_ALERT_TITLE);
                });
    }

    private void configureRequest(String method, String pathInfo) {
        lenient().when(request.getMethod()).thenReturn(method);
        lenient().when(request.getPathInfo()).thenReturn(pathInfo);
    }

    private void withBody(String body) {
        when(request.getContentLengthLong()).thenReturn((long) body.length());
        asUnchecked(() -> lenient().when(request.getInputStream()).thenReturn(FakeServletInputStream.of(body)));
    }
}
