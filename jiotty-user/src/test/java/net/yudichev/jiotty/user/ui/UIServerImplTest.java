package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.options.Option;
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

/// Façade-level tests for [UIServerImpl]. The substantive behaviour (option persistence, displayable throttling, SSE choreography, individual handler logic)
/// lives in [OptionRegistryImpl], [DisplayableRegistryImpl], [SseServiceImpl], and each [ApiPathHandler]; those have their own tests. This file only verifies
/// the façade contract: registration delegates to the appropriate registry, and `dispatchApiPath` resolves handlers by longest-prefix-wins.
@ExtendWith(MockitoExtension.class)
class UIServerImplTest {
    @Mock
    private OptionRegistry optionRegistry;
    @Mock
    private DisplayableRegistry displayableRegistry;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private Closeable optionRegistration;
    @Mock
    private Closeable displayableRegistration;

    private UIServerImpl server;

    @BeforeEach
    void setUp() {
        lenient().when(request.getContextPath()).thenReturn("/ui/api");
        lenient().when(request.getServletPath()).thenReturn("");
        server = new UIServerImpl(optionRegistry, displayableRegistry, Set.of());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void registerOption_delegatesToRegistry(@Mock Option<?> option) {
        when(optionRegistry.register(option)).thenAnswer(_ -> optionRegistration);
        assertThat(server.registerOption(option)).isSameAs(optionRegistration);
    }

    @Test
    void registerDisplayable_delegatesToRegistry(@Mock Displayable displayable) {
        when(displayableRegistry.register(displayable)).thenAnswer(_ -> displayableRegistration);
        assertThat(server.registerDisplayable(displayable)).isSameAs(displayableRegistration);
    }

    @Test
    void dispatchApiPath_noPathInfo_returnsFalse() {
        when(request.getPathInfo()).thenReturn(null);
        assertThat(server.dispatchApiPath(request, response)).isFalse();
    }

    @Test
    void dispatchApiPath_noHandlersMatch_returnsFalse(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/something");
        server = serverWith(handler);
        when(request.getPathInfo()).thenReturn("/other");
        assertThat(server.dispatchApiPath(request, response)).isFalse();
        verify(handler, never()).handle(request, response);
    }

    @Test
    void dispatchApiPath_exactPrefix_dispatches(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        server = serverWith(handler);
        when(request.getPathInfo()).thenReturn("/analytics");
        assertThat(server.dispatchApiPath(request, response)).isTrue();
        verify(handler).handle(request, response);
    }

    @Test
    void dispatchApiPath_prefixWithSubpath_dispatches(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        server = serverWith(handler);
        when(request.getPathInfo()).thenReturn("/analytics/reports/iog");
        assertThat(server.dispatchApiPath(request, response)).isTrue();
        verify(handler).handle(request, response);
    }

    @Test
    void dispatchApiPath_stringPrefixButNotPathPrefix_doesNotDispatch(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        server = serverWith(handler);
        when(request.getPathInfo()).thenReturn("/analytics-other");
        assertThat(server.dispatchApiPath(request, response)).isFalse();
        verify(handler, never()).handle(request, response);
    }

    @Test
    void dispatchApiPath_setsRouteNameAttributeBeforeInvokingHandler(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/analytics");
        server = serverWith(handler);
        when(request.getPathInfo()).thenReturn("/analytics/savings");

        assertThat(server.dispatchApiPath(request, response)).isTrue();

        var inOrder = inOrder(request, handler);
        inOrder.verify(request).setAttribute(UIHttpServerImpl.ROUTE_NAME_ATTRIBUTE, "/ui/api/analytics");
        inOrder.verify(handler).handle(request, response);
    }

    @Test
    void dispatchApiPath_noHandlerMatches_doesNotSetRouteNameAttribute(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "/something");
        server = serverWith(handler);
        when(request.getPathInfo()).thenReturn("/other");

        assertThat(server.dispatchApiPath(request, response)).isFalse();
        verify(request, never()).setAttribute(UIHttpServerImpl.ROUTE_NAME_ATTRIBUTE, "/ui/api/something");
    }

    @Test
    void dispatchApiPath_longestPrefixWins(@Mock ApiPathHandler shortHandler, @Mock ApiPathHandler longHandler) {
        stubPrefix(shortHandler, "/analytics");
        stubPrefix(longHandler, "/analytics/special");
        server = serverWith(shortHandler, longHandler);
        when(request.getPathInfo()).thenReturn("/analytics/special/foo");
        assertThat(server.dispatchApiPath(request, response)).isTrue();
        verify(longHandler).handle(request, response);
        verify(shortHandler, never()).handle(request, response);
    }

    @Test
    void start_rejectsPrefixWithoutLeadingSlash(@Mock ApiPathHandler handler) {
        stubPrefix(handler, "analytics");
        var bad = new UIServerImpl(optionRegistry, displayableRegistry, Set.of(handler));
        assertThatThrownBy(bad::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with");
    }

    @Test
    void start_rejectsDuplicatePrefix(@Mock ApiPathHandler first, @Mock ApiPathHandler second) {
        stubPrefix(first, "/dup");
        stubPrefix(second, "/dup");
        var bad = new UIServerImpl(optionRegistry, displayableRegistry, orderedSet(first, second));
        assertThatThrownBy(bad::start)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("/dup");
    }

    private UIServerImpl serverWith(ApiPathHandler... handlers) {
        server.stop();
        var newServer = new UIServerImpl(optionRegistry, displayableRegistry, orderedSet(handlers));
        newServer.start();
        return newServer;
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
