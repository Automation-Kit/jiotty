package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.StreamInvalidationSubscription;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BaseSseApiPathHandlerTest {
    private static final String PATH = "/things/stream";

    @Mock
    private StreamStarter streamStarter;
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

    private BaseSseApiPathHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BaseSseApiPathHandler(PATH) {
            @Override
            protected Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) throws IOException {
                return streamStarter.start(request, response, onStreamClosed);
            }
        };
    }

    @Test
    void claimsItsPathAsThePrefix() {
        assertThat(handler.pathPrefix()).isEqualTo(PATH);
    }

    @Test
    void refusesAPathWithoutItsLeadingSlash() {
        assertThatThrownBy(() -> new BaseSseApiPathHandler("stream") {
            @Override
            protected Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) {
                return Closeable.noop();
            }
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must start with '/'");
    }

    @Test
    void wrongPathReturns404() throws IOException {
        when(request.getPathInfo()).thenReturn(PATH + "/extra");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        handler.handle(request, response);

        verify(response).setStatus(NOT_FOUND_404);
        verify(streamStarter, never()).start(any(), any(), any());
    }

    @Test
    void wrongMethodReturns405() throws IOException {
        when(request.getPathInfo()).thenReturn(PATH);
        when(request.getMethod()).thenReturn("POST");

        handler.handle(request, response);

        verify(response).setStatus(METHOD_NOT_ALLOWED_405);
        verify(streamStarter, never()).start(any(), any(), any());
    }

    @Test
    void invalidationCallbackClosesSseStream() throws IOException {
        configureValidRequest();
        var capturedInvalidationCallback = new MutableReference<Runnable>();
        when(streamStarter.start(any(), any(), any())).thenReturn(sseStream);
        when(streamInvalidationSubscription.subscribe(any())).thenAnswer(inv -> {
            capturedInvalidationCallback.set(inv.getArgument(0));
            return Closeable.noop();
        });

        handler.handle(request, response);

        capturedInvalidationCallback.get().run();

        verify(sseStream).close();
    }

    @Test
    void sseClosedBeforeInvalidationSubscriptionSet_subscriptionClosedImmediately(@Mock Closeable invalidationSubscription) throws IOException {
        configureValidRequest();
        when(streamStarter.start(any(), any(), any())).thenAnswer(inv -> {
            // The stream ends while it is still being started, before the handler has subscribed to the request's invalidation.
            inv.<Runnable>getArgument(2).run();
            return sseStream;
        });
        when(streamInvalidationSubscription.subscribe(any())).thenReturn(invalidationSubscription);

        handler.handle(request, response);

        // A stream already ended holds nothing open, so the subscription taken after it is released at once.
        verify(invalidationSubscription).close();
    }

    @Test
    void sseClosedAfterInvalidationSubscriptionSet_subscriptionReleasedThen(@Mock Closeable invalidationSubscription) throws IOException {
        configureValidRequest();
        var capturedOnStreamClosed = new MutableReference<Runnable>();
        when(streamStarter.start(any(), any(), any())).thenAnswer(inv -> {
            capturedOnStreamClosed.set(inv.getArgument(2));
            return sseStream;
        });
        when(streamInvalidationSubscription.subscribe(any())).thenReturn(invalidationSubscription);

        handler.handle(request, response);
        verify(invalidationSubscription, never()).close();

        // The stream ends long after the handler returned, as when the client goes away.
        capturedOnStreamClosed.get().run();

        verify(invalidationSubscription).close();
    }

    private void configureValidRequest() {
        when(request.getPathInfo()).thenReturn(PATH);
        when(request.getMethod()).thenReturn("GET");
        var context = new UIRequestContext(runtime, streamInvalidationSubscription);
        when(request.getAttribute(RequestContextFilter.REQUEST_CONTEXT_ATTRIBUTE)).thenReturn(context);
    }

    interface StreamStarter {
        Closeable start(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) throws IOException;
    }
}
