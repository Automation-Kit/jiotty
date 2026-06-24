package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.user.ui.UIServerRuntime.DispatchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Tests for the pure request dispatcher [UIServerRuntimeImpl]: handlers are resolved by longest-prefix-wins, the route-name attribute is set before the
/// handler runs, prefix validation rejects malformed or colliding prefixes at start, and a stopped runtime degrades to [DispatchResult#UNAVAILABLE] rather
/// than throwing.
@ExtendWith(MockitoExtension.class)
class UIServerRuntimeImplTest {
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;

    private UIServerRuntimeImpl runtime;

    @BeforeEach
    void setUp() {
        lenient().when(request.getContextPath()).thenReturn("/ui/api");
        lenient().when(request.getServletPath()).thenReturn("");
        runtime = new UIServerRuntimeImpl(Set.of());
        runtime.start();
    }

    @AfterEach
    void tearDown() {
        runtime.stop();
    }

    @Test
    void dispatchApiPath_noPathInfo_returnsNotFound() {
        when(request.getPathInfo()).thenReturn(null);
        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.NOT_FOUND);
    }

    @Test
    void dispatchApiPath_noHandlersMatch_returnsNotFound(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/something");
        runtime = runtimeWith(handler);
        when(request.getPathInfo()).thenReturn("/other");
        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.NOT_FOUND);
        verify(handler, never()).handle(request, response);
    }

    @Test
    void dispatchApiPath_exactPrefix_dispatches(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        runtime = runtimeWith(handler);
        when(request.getPathInfo()).thenReturn("/analytics");
        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.HANDLED);
        verify(handler).handle(request, response);
    }

    @Test
    void dispatchApiPath_prefixWithSubpath_dispatches(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        runtime = runtimeWith(handler);
        when(request.getPathInfo()).thenReturn("/analytics/reports/iog");
        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.HANDLED);
        verify(handler).handle(request, response);
    }

    @Test
    void dispatchApiPath_stringPrefixButNotPathPrefix_doesNotDispatch(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        runtime = runtimeWith(handler);
        when(request.getPathInfo()).thenReturn("/analytics-other");
        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.NOT_FOUND);
        verify(handler, never()).handle(request, response);
    }

    @Test
    void dispatchApiPath_setsRouteNameAttributeBeforeInvokingHandler(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        runtime = runtimeWith(handler);
        when(request.getPathInfo()).thenReturn("/analytics/savings");

        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.HANDLED);

        var inOrder = inOrder(request, handler);
        inOrder.verify(request).setAttribute(UIHttpServerImpl.ROUTE_NAME_ATTRIBUTE, "/ui/api/analytics");
        inOrder.verify(handler).handle(request, response);
    }

    @Test
    void dispatchApiPath_noHandlerMatches_doesNotSetRouteNameAttribute(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/something");
        runtime = runtimeWith(handler);
        when(request.getPathInfo()).thenReturn("/other");

        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.NOT_FOUND);
        verify(request, never()).setAttribute(UIHttpServerImpl.ROUTE_NAME_ATTRIBUTE, "/ui/api/something");
    }

    @Test
    void dispatchApiPath_longestPrefixWins(@Mock ApiPathHandler shortHandler, @Mock ApiPathHandler longHandler) {
        stubPrefix(shortHandler, "/analytics");
        stubPrefix(longHandler, "/analytics/special");
        runtime = runtimeWith(shortHandler, longHandler);
        when(request.getPathInfo()).thenReturn("/analytics/special/foo");
        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.HANDLED);
        verify(longHandler).handle(request, response);
        verify(shortHandler, never()).handle(request, response);
    }

    @Test
    void dispatchApiPath_whenStopped_returnsUnavailableWithoutInvokingHandler(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        runtime = runtimeWith(handler);
        lenient().when(request.getPathInfo()).thenReturn("/analytics");

        runtime.stop();   // mirrors a stopped runtime mid-request

        assertThat(runtime.dispatchApiPath(request, response)).isEqualTo(DispatchResult.UNAVAILABLE);
        verify(handler, never()).handle(request, response);
    }

    @Test
    void start_rejectsPrefixWithoutLeadingSlash(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "analytics");
        var bad = new UIServerRuntimeImpl(Set.of(handler));
        assertThatThrownBy(bad::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with");
    }

    @Test
    void start_rejectsDuplicatePrefix(@Mock ApiPathHandler first, @Mock ApiPathHandler second) {
        stubPrefix(first, "/dup");
        stubPrefix(second, "/dup");
        var bad = new UIServerRuntimeImpl(orderedSet(first, second));
        assertThatThrownBy(bad::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/dup");
    }

    private UIServerRuntimeImpl runtimeWith(ApiPathHandler... handlers) {
        runtime.stop();
        var newRuntime = new UIServerRuntimeImpl(orderedSet(handlers));
        newRuntime.start();
        return newRuntime;
    }

    private static void stubPrefix(ApiPathHandler handler, String prefix) {
        when(handler.pathPrefix()).thenReturn(prefix);
    }

    private static Set<ApiPathHandler> orderedSet(ApiPathHandler... handlers) {
        var set = new LinkedHashSet<ApiPathHandler>(handlers.length);
        Collections.addAll(set, handlers);
        return set;
    }
}
