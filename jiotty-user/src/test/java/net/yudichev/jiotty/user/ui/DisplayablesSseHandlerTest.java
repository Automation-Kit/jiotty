package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.StreamInvalidationSubscription;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// The path and invalidation handling are [BaseSseApiPathHandler]'s, tested in [BaseSseApiPathHandlerTest]; this covers what the subclass adds.
@ExtendWith(MockitoExtension.class)
class DisplayablesSseHandlerTest {

    @Mock
    private SseService sseService;
    @Mock
    private UIServerRuntime runtime;
    @Mock
    private StreamInvalidationSubscription streamInvalidationSubscription;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private Closeable sseStream;

    private DisplayablesSseHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DisplayablesSseHandler(sseService);
    }

    @Test
    void servesTheDisplayablesStreamPath() {
        assertThat(handler.pathPrefix()).isEqualTo("/displayables/stream");
    }

    @Test
    void getDelegatesToSseService_andSubscribesToContextInvalidation() {
        when(request.getPathInfo()).thenReturn("/displayables/stream");
        when(request.getMethod()).thenReturn("GET");
        var context = new UIRequestContext(runtime, streamInvalidationSubscription);
        when(request.getAttribute(RequestContextFilter.REQUEST_CONTEXT_ATTRIBUTE)).thenReturn(context);
        when(sseService.startSse(any(), any(), any())).thenReturn(sseStream);
        when(streamInvalidationSubscription.subscribe(any())).thenReturn(Closeable.noop());

        handler.handle(request, response);

        verify(sseService).startSse(any(), any(), any());
        verify(streamInvalidationSubscription).subscribe(any());
    }
}
