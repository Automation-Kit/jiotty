package net.yudichev.jiotty.user.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.StreamInvalidationSubscription;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashSet;
import java.util.Set;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UIHttpServerImplTest {
    @Mock
    private UIRequestAuthoriser requestAuthoriser;
    @Mock
    private UIServerRuntime runtime;
    @Mock
    private StreamInvalidationSubscription streamInvalidationSubscription;

    private UIHttpServerImpl server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        server = new UIHttpServerImpl(0, defaultMounts());
        server.start();
        httpClient = HttpClient.newBuilder()
                               .followRedirects(HttpClient.Redirect.NEVER)
                               .build();

        asUnchecked(() -> lenient().doAnswer(invocation -> {
            var request = (HttpServletRequest) invocation.getArgument(0);
            var response = (HttpServletResponse) invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            RequestContextFilter.setRequestContext(request, new UIRequestContext(runtime, streamInvalidationSubscription));
            chain.doFilter(request, response);
            return null;
        }).when(requestAuthoriser).authorise(any(), any(), any()));

        lenient().when(streamInvalidationSubscription.subscribe(any())).thenAnswer(_ -> Closeable.noop());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 65_536})
    void invalidPort_throwsException(int port) {
        assertThatThrownBy(() -> new UIHttpServerImpl(port, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listenPort_returnsAssignedPort() {
        assertThat(server.listenPort()).isGreaterThan(0);
    }

    @Test
    void staticResourceGet_servesFile() {
        HttpResponse<String> response = sendGet("/ui/style.css");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotEmpty();
        verifyNoInteractions(requestAuthoriser);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void apiUnknownPath_noHandlerMatches_returns404(String method) {
        HttpResponse<String> response = sendRequest(method, "/ui/api/unknown");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"error\":\"Unknown path\"}");
        verify(runtime).dispatchApiPath(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void apiUnknownPath_handlerMatches_runtimeWritesResponse(String method) {
        asUnchecked(() -> when(runtime.dispatchApiPath(any(), any())).thenAnswer(invocation -> {
            HttpServletResponse resp = invocation.getArgument(1);
            resp.setStatus(200);
            resp.setContentType("text/plain");
            resp.getWriter().print("handled-by-api-path-handler");
            return true;
        }));

        HttpResponse<String> response = sendRequest(method, "/ui/api/analytics/some-report");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("handled-by-api-path-handler");
        verify(runtime).dispatchApiPath(any(), any());
    }

    @Test
    void servletMount_handlesRequestAtMountedPath() {
        server.stop();

        var mounts = new LinkedHashSet<>(defaultMounts());
        mounts.add(textMount("/mounted", "/hello", "mounted-response"));
        server = new UIHttpServerImpl(0, mounts);
        server.start();

        HttpResponse<String> response = sendGet("/mounted/hello");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("mounted-response");
        verifyNoInteractions(requestAuthoriser);
    }

    @Test
    void multipleServletMounts_eachServesItsOwnContext() {
        server.stop();

        var mounts = new LinkedHashSet<>(defaultMounts());
        mounts.add(textMount("/first", "/hello", "first-response"));
        mounts.add(textMount("/second", "/hello", "second-response"));
        server = new UIHttpServerImpl(0, mounts);
        server.start();

        HttpResponse<String> firstResponse = sendGet("/first/hello");
        HttpResponse<String> secondResponse = sendGet("/second/hello");

        assertThat(firstResponse.statusCode()).isEqualTo(200);
        assertThat(firstResponse.body()).isEqualTo("first-response");
        assertThat(secondResponse.statusCode()).isEqualTo(200);
        assertThat(secondResponse.body()).isEqualTo("second-response");
        verifyNoInteractions(requestAuthoriser);
    }

    @Test
    void overlappingContexts_routeByLongestContextPath() {
        // Verifies that /ui/api/* is routed to the API mount even though /ui is a shorter-prefix sibling.
        HttpResponse<String> staticResponse = sendGet("/ui/style.css");
        HttpResponse<String> apiResponse = sendGet("/ui/api/displayables/unknown");

        assertThat(staticResponse.statusCode()).isEqualTo(200);
        assertThat(staticResponse.body()).isNotEmpty();
        // /ui/api/* reaches the runtime's dispatchApiPath even when the path doesn't match any handler.
        verify(runtime).dispatchApiPath(any(), any());
        assertThat(apiResponse.statusCode()).isEqualTo(404);
    }

    private Set<ServletMount> defaultMounts() {
        return Set.of(new ApiServletMount(requestAuthoriser), new StaticResourceServletMount());
    }

    private static ServletMount textMount(String contextPath, String servletPathSpec, String body) {
        return () -> {
            var contextHandler = new ServletContextHandler();
            contextHandler.setContextPath(contextPath);
            var servlet = new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    resp.setStatus(200);
                    resp.setContentType("text/plain");
                    resp.getWriter().print(body);
                }
            };
            contextHandler.addServlet(new ServletHolder(servlet), servletPathSpec);
            return contextHandler;
        };
    }

    private String baseUrl() {
        return "http://localhost:" + server.listenPort();
    }

    private HttpResponse<String> sendGet(String path) {
        return getAsUnchecked(() -> httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    private HttpResponse<String> sendRequest(String method, String path) {
        HttpRequest.BodyPublisher body = "POST".equals(method)
                                         ? HttpRequest.BodyPublishers.ofString("")
                                         : HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                                                 .uri(URI.create(baseUrl() + path))
                                                 .method(method, body);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/x-www-form-urlencoded");
        }
        return getAsUnchecked(() -> httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString()));
    }
}
