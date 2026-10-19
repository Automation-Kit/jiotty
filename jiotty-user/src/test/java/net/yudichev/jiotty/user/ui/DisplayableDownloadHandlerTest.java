package net.yudichev.jiotty.user.ui;

import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.TestAdminAlertService;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.TextBody;
import net.yudichev.jiotty.user.ui.io.testing.DispatchedRequest;
import net.yudichev.jiotty.user.ui.io.testing.FakeAsyncContext;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.user.ui.DisplayableDownloadHandler.DISPLAYABLE_ID_PARAMETER;
import static net.yudichev.jiotty.user.ui.DisplayableDownloadHandler.DOWNLOAD_ID_PARAMETER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisplayableDownloadHandlerTest {
    private final TestAdminAlertService alertService = new TestAdminAlertService();
    private ProgrammableClock clock;
    private DisplayableRegistryImpl registry;
    private DisplayableDownloadHandler handler;

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    private FakeAsyncContext asyncContext;
    /// `null` until a test dispatches the request.
    private @Nullable DispatchedRequest dispatchedRequest;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        Provider<SchedulingExecutor> executorProvider = () -> clock.createSingleThreadedSchedulingExecutor("test");
        registry = new DisplayableRegistryImpl(executorProvider);
        registry.start();
        handler = new DisplayableDownloadHandler(registry, alertService, "user-1");
        handler.start();
        clock.tick();
        asyncContext = new FakeAsyncContext(request, response);
        lenient().when(request.startAsync()).thenReturn(asyncContext);
        lenient().when(request.getPathInfo()).thenReturn(DisplayableDownloadHandler.PATH);
        lenient().when(request.getMethod()).thenReturn("GET");
    }

    /// Once the exchange has started, a deadline can complete it and the container recycle the request at any moment, so nothing reads it after dispatch.
    @AfterEach
    void tearDown() {
        handler.stop();
        registry.stop();
        clock.tick();
        if (dispatchedRequest != null) {
            assertThat(dispatchedRequest.callsAfterDispatch()).isEmpty();
        }
    }

    @Test
    void unknownDisplayableIdReturns404() throws IOException {
        var responseOutput = new CapturingServletOutputStream();
        when(request.getParameter(DISPLAYABLE_ID_PARAMETER)).thenReturn("nonexistent");
        when(response.getOutputStream()).thenReturn(responseOutput);

        dispatch();
        clock.tick();

        verify(response).setStatus(NOT_FOUND_404);
        assertThat(responseOutput.output()).contains(DisplayableDownloadHandler.UNKNOWN_DISPLAYABLE_MESSAGE_PREFIX + "nonexistent");
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    @Test
    void missingDisplayableIdReturns404() throws IOException {
        var responseOutput = new CapturingServletOutputStream();
        when(response.getOutputStream()).thenReturn(responseOutput);

        dispatch();
        clock.tick();

        verify(response).setStatus(NOT_FOUND_404);
        assertThat(responseOutput.output()).contains(DisplayableDownloadHandler.UNKNOWN_DISPLAYABLE_MESSAGE_PREFIX);
        assertThat(alertService.activeAlertsById()).isEmpty();
    }

    @Test
    void missingDownloadIdReturns404() throws IOException {
        registerDisplayable("d1");
        clock.tick();
        var responseOutput = new CapturingServletOutputStream();
        when(request.getParameter(DISPLAYABLE_ID_PARAMETER)).thenReturn("d1");
        when(request.getParameter(DOWNLOAD_ID_PARAMETER)).thenReturn(null);
        when(response.getOutputStream()).thenReturn(responseOutput);

        dispatch();
        clock.tick();

        verify(response).setStatus(NOT_FOUND_404);
        assertThat(responseOutput.output()).contains(DisplayableDownloadHandler.MISSING_DOWNLOAD_ID_MESSAGE);
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    @Test
    void answersWithTheDisplayablesDownloadAnswer() throws IOException {
        var displayable = registerDisplayable("d1");
        when(displayable.handleDownload("dl-1")).thenReturn(completedFuture(Answer.of(OK_200, new TextBody("1.5\n2.5\n"))));
        clock.tick();

        var responseOutput = new CapturingServletOutputStream();
        whenRequestNames("d1", "dl-1");
        when(response.getOutputStream()).thenReturn(responseOutput);

        dispatch();
        clock.tick();

        verify(response).setStatus(OK_200);
        assertThat(responseOutput.output()).isEqualTo("1.5\n2.5\n");
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    @Test
    void downloadFailureWritesErrorAndReturns400() throws IOException {
        var displayable = registerDisplayable("d1");
        when(displayable.handleDownload("dl-1")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("download exploded")));
        clock.tick();

        var responseOutput = new CapturingServletOutputStream();
        whenRequestNames("d1", "dl-1");
        when(response.getOutputStream()).thenReturn(responseOutput);

        dispatch();
        clock.tick();

        verify(response).setStatus(BAD_REQUEST_400);
        assertThat(responseOutput.output()).contains("download exploded");
        assertThat(asyncContext.completions()).isEqualTo(1);
    }

    /// A displayable throwing while it starts the download is a defect on this server's side, so it is alerted and answered as a server fault at once.
    @Test
    void aDownloadThatThrowsIsAnsweredAsAServerFault() throws IOException {
        var displayable = registerDisplayable("d1");
        when(displayable.handleDownload("dl-1")).thenThrow(new IllegalStateException("broken"));
        clock.tick();
        whenRequestNames("d1", "dl-1");
        when(response.getOutputStream()).thenReturn(new CapturingServletOutputStream());

        dispatch();
        clock.tick();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> assertThat(alert.title()).isEqualTo(DisplayableDownloadHandler.DOWNLOAD_FAILED_ALERT_TITLE));
        assertThat(asyncContext.pendingDeadlines()).isEmpty();
    }

    /// A download that never completes is answered with a server fault once its deadline passes, and alerted.
    @Test
    void aDownloadThatNeverCompletesIsAnsweredByTheDeadline() throws IOException {
        var displayable = registerDisplayable("d1");
        when(displayable.handleDownload("dl-1")).thenReturn(new CompletableFuture<>());
        clock.tick();
        whenRequestNames("d1", "dl-1");
        when(response.getOutputStream()).thenReturn(new CapturingServletOutputStream());
        dispatch();
        clock.tick();
        assertThat(asyncContext.pendingDeadlines()).containsExactly(HandlerDeadlines.TIMEOUT);

        asyncContext.expireDeadlines();

        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
        assertThat(alertService.activeAlertsById().values())
                .singleElement()
                .satisfies(alert -> assertThat(alert.title()).isEqualTo(DisplayableDownloadHandler.TIMED_OUT_ALERT_TITLE));
    }

    @Test
    void wrongPathReturnsUnknownPath() throws IOException {
        var responseBody = new CapturingServletOutputStream();
        when(request.getPathInfo()).thenReturn(DisplayableDownloadHandler.PATH + "/something");
        when(response.getOutputStream()).thenReturn(responseBody);

        handler.handle(request, response);

        verify(response).setStatus(NOT_FOUND_404);
        assertThat(responseBody.output()).contains(ApiServlet.UNKNOWN_PATH_ERROR);
    }

    @Test
    void wrongMethodReturns405() {
        when(request.getMethod()).thenReturn("POST");

        handler.handle(request, response);

        verify(response).setStatus(METHOD_NOT_ALLOWED_405);
    }

    private void whenRequestNames(String displayableId, String downloadId) {
        when(request.getParameter(DISPLAYABLE_ID_PARAMETER)).thenReturn(displayableId);
        when(request.getParameter(DOWNLOAD_ID_PARAMETER)).thenReturn(downloadId);
    }

    private void dispatch() {
        dispatchedRequest = DispatchedRequest.dispatch(request, asyncContext, dispatched -> handler.handle(dispatched, response));
    }

    private Displayable registerDisplayable(String id) {
        var displayable = Mockito.mock(Displayable.class);
        when(displayable.getId()).thenReturn(id);
        lenient().when(displayable.getDisplayName()).thenReturn(id);
        lenient().when(displayable.supportsData()).thenReturn(false);
        lenient().when(displayable.visible()).thenReturn(true);
        registry.register(displayable);
        return displayable;
    }
}
