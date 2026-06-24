package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.StreamInvalidationSubscription;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;
import net.yudichev.jiotty.user.ui.UIServerRuntime.DispatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Verifies [ApiServlet] renders each [DispatchResult]: HANDLED writes nothing extra, NOT_FOUND → 404, UNAVAILABLE → a retryable 503.
@ExtendWith(MockitoExtension.class)
class ApiServletTest {
    private final ApiServlet servlet = new ApiServlet();
    private final StringWriter responseBody = new StringWriter();
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
        stubWriter();
        when(runtime.dispatchApiPath(request, response)).thenReturn(DispatchResult.NOT_FOUND);

        servlet.service(request, response);

        verify(response).setStatus(404);
        assertThat(responseBody.toString()).contains("Unknown path");
    }

    @Test
    void unavailable_writesRetryable503() {
        stubWriter();
        when(runtime.dispatchApiPath(request, response)).thenReturn(DispatchResult.UNAVAILABLE);

        servlet.service(request, response);

        verify(response).setStatus(503);
        verify(response).setHeader("Retry-After", "1");
        assertThat(responseBody.toString()).contains("Temporarily unavailable");
    }

    private void stubWriter() {
        asUnchecked(() -> when(response.getWriter()).thenReturn(new PrintWriter(responseBody)));
    }
}
