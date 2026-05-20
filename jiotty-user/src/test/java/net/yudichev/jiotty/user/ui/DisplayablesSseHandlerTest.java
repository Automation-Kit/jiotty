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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void wrongPathReturns404() throws IOException {
        when(request.getPathInfo()).thenReturn("/displayables/stream/extra");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        handler.handle(request, response);

        verify(response).setStatus(404);
        verify(sseService, never()).startSse(any(), any(), any());
    }

    @Test
    void wrongMethodReturns405() throws IOException {
        when(request.getPathInfo()).thenReturn("/displayables/stream");
        when(request.getMethod()).thenReturn("POST");

        handler.handle(request, response);

        verify(response).setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        verify(sseService, never()).startSse(any(), any(), any());
    }

    @Test
    void getDelegatesToSseService_andSubscribesToContextInvalidation() throws IOException {
        configureValidRequest();
        when(sseService.startSse(any(), any(), any())).thenReturn(sseStream);
        when(streamInvalidationSubscription.subscribe(any())).thenReturn(Closeable.noop());

        handler.handle(request, response);

        verify(sseService).startSse(any(), any(), any());
        verify(streamInvalidationSubscription).subscribe(any());
    }

    @Test
    void invalidationCallbackClosesSseStream() throws IOException {
        configureValidRequest();
        var capturedInvalidationCallback = new AtomicReference<Runnable>();
        when(sseService.startSse(any(), any(), any())).thenReturn(sseStream);
        when(streamInvalidationSubscription.subscribe(any())).thenAnswer(inv -> {
            capturedInvalidationCallback.set(inv.getArgument(0));
            return Closeable.noop();
        });

        handler.handle(request, response);

        capturedInvalidationCallback.get().run();

        verify(sseStream).close();
    }

    @Test
    void sseClosedBeforeInvalidationSubscriptionSet_subscriptionClosedImmediately() throws IOException {
        configureValidRequest();
        var capturedOnStreamClosed = new AtomicReference<Runnable>();
        when(sseService.startSse(any(), any(), any())).thenAnswer(inv -> {
            capturedOnStreamClosed.set(inv.getArgument(2));
            // Simulate the stream closing synchronously inside startSse, BEFORE subscribeToInvalidation sets the ref.
            capturedOnStreamClosed.get().run();
            return sseStream;
        });
        Closeable invalidationSubscription = Mockito.mock(Closeable.class);
        when(streamInvalidationSubscription.subscribe(any())).thenReturn(invalidationSubscription);

        handler.handle(request, response);

        // The handler's "if (streamClosed.get())" branch closes the invalidation subscription that was just set.
        verify(invalidationSubscription).close();
    }

    private void configureValidRequest() {
        when(request.getPathInfo()).thenReturn("/displayables/stream");
        when(request.getMethod()).thenReturn("GET");
        var context = new UIRequestContext(runtime, streamInvalidationSubscription);
        when(request.getAttribute(RequestContextFilter.REQUEST_CONTEXT_ATTRIBUTE)).thenReturn(context);
    }
}
