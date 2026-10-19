package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.StreamInvalidationSubscription;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;
import net.yudichev.jiotty.user.ui.UIServerRuntime.DispatchResult;
import net.yudichev.jiotty.user.ui.sse.testing.CapturingServletOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.net.HttpHeaders.RETRY_AFTER;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Verifies [ApiServlet] renders each [DispatchResult]: HANDLED writes nothing extra, NOT_FOUND → 404, UNAVAILABLE → a retryable 503.
@ExtendWith(MockitoExtension.class)
class ApiServletTest {
    private final ApiServlet servlet = new ApiServlet();
    private final CapturingServletOutputStream responseBody = new CapturingServletOutputStream();
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private UIServerRuntime runtime;

    @BeforeEach
    void setUp() {
        StreamInvalidationSubscription subscription = _ -> (Closeable) () -> {};
        when(request.getAttribute(RequestContextFilter.REQUEST_CONTEXT_ATTRIBUTE)).thenReturn(new UIRequestContext(runtime, subscription));
    }

    @Test
    void handled_writesNothingExtra() {
        when(runtime.dispatchApiPath(request, response)).thenReturn(DispatchResult.HANDLED);

        servlet.service(request, response);

        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void notFound_writes404() {
        stubOutputStream();
        when(runtime.dispatchApiPath(request, response)).thenReturn(DispatchResult.NOT_FOUND);

        servlet.service(request, response);

        verify(response).setStatus(NOT_FOUND_404);
        assertThat(responseBody.output()).contains(ApiServlet.UNKNOWN_PATH_ERROR);
    }

    @Test
    void unavailable_writesRetryable503() {
        stubOutputStream();
        when(runtime.dispatchApiPath(request, response)).thenReturn(DispatchResult.UNAVAILABLE);

        servlet.service(request, response);

        verify(response).setStatus(SERVICE_UNAVAILABLE_503);
        verify(response).setHeader(RETRY_AFTER, "1");
        assertThat(responseBody.output()).contains(ApiServlet.UNAVAILABLE_ERROR);
    }

    private void stubOutputStream() {
        asUnchecked(() -> when(response.getOutputStream()).thenReturn(responseBody));
    }
}
