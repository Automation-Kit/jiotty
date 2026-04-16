package net.yudichev.jiotty.user.ui;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.StreamInvalidationSubscription;
import net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        server = new UIHttpServerImpl(requestAuthoriser, 0);
        server.start();
        httpClient = HttpClient.newBuilder()
                               .followRedirects(HttpClient.Redirect.NEVER)
                               .build();

        asUnchecked(() -> lenient().doAnswer(invocation -> {
            var request = (HttpServletRequest) invocation.getArgument(0);
            var response = (HttpServletResponse) invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            UIHttpServerImpl.setRequestContext(request, new UIRequestContext(runtime, streamInvalidationSubscription));
            chain.doFilter(request, response);
            return null;
        }).when(requestAuthoriser).authorise(any(), any(), any()));

        asUnchecked(() -> lenient().when(runtime.startSse(any(), any(), any())).thenReturn(Closeable.noop()));
        lenient().when(streamInvalidationSubscription.subscribe(any())).thenReturn(Closeable.noop());
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
        assertThatThrownBy(() -> new UIHttpServerImpl(requestAuthoriser, port))
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

    @Test
    void optionsGet_redirectsToIndex() {
        HttpResponse<String> response = sendGet("/ui/options");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location).endsWith("/ui/index.html"));
        verifyNoInteractions(requestAuthoriser);
    }

    @Test
    void optionsPost_callsHandleOptionsPost() {
        HttpResponse<String> response = sendPost("/ui/options");

        assertThat(response.statusCode()).isEqualTo(200);
        verify(runtime).handleOptionsPost(any(), any());
    }

    @Test
    void downloadGet_callsHandleDownload() {
        sendGet("/ui/displayables/test-file");

        verify(runtime).handleDownload(any(), any());
    }

    @Test
    void apiGetDisplayables_callsGetDisplayablesList() {
        sendGet("/ui/api/displayables");

        asUnchecked(() -> verify(runtime).handleGetDisplayablesList(any()));
    }

    @Test
    void apiGetDisplayableItem_callsGetDisplayableItem() {
        sendGet("/ui/api/displayables/item");

        asUnchecked(() -> verify(runtime).handleGetDisplayableItem(any(), any()));
    }

    @Test
    void apiGetDisplayablesStream_startsSseStream() {
        sendGet("/ui/api/displayables/stream");

        asUnchecked(() -> verify(runtime).startSse(any(), any(), any()));
    }

    @Test
    void apiPostPushDevices_callsPushDeviceRegister() {
        HttpResponse<String> response = sendPost("/ui/api/push/devices");

        assertThat(response.statusCode()).isEqualTo(200);
        verify(runtime).handlePushDeviceRegister(any(), any());
    }

    @Test
    void apiDeletePushDevice_callsPushDeviceUnregister() {
        HttpResponse<String> response = sendDelete("/ui/api/push/devices/device123");

        assertThat(response.statusCode()).isEqualTo(200);
        verify(runtime).handlePushDeviceUnregister(eq("device123"), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "DELETE"})
    void apiUnknownPath_returns404(String method) {
        HttpResponse<String> response = sendRequest(method, "/ui/api/unknown");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).isEqualTo("{\"error\":\"Unknown path\"}");
    }

    @Test
    void sseStream_invalidation_closesSseStream(@Mock Closeable sseStreamCloseable) {
        asUnchecked(() -> when(runtime.startSse(any(), any(), any())).thenReturn(sseStreamCloseable));
        var capturedOnInvalidated = new MutableReference<Runnable>();
        when(streamInvalidationSubscription.subscribe(any())).thenAnswer(invocation -> {
            capturedOnInvalidated.set(invocation.getArgument(0));
            return Closeable.noop();
        });

        sendGet("/ui/api/displayables/stream");
        capturedOnInvalidated.get().run();

        verify(sseStreamCloseable).close();
    }

    @Test
    void sseStream_closed_closesInvalidationSubscription(@Mock Closeable invalidationCloseable) {
        var capturedOnStreamClosed = new MutableReference<Runnable>();
        asUnchecked(() -> when(runtime.startSse(any(), any(), any())).thenAnswer(invocation -> {
            capturedOnStreamClosed.set(invocation.getArgument(2));
            return Closeable.noop();
        }));
        when(streamInvalidationSubscription.subscribe(any())).thenReturn(invalidationCloseable);

        sendGet("/ui/api/displayables/stream");
        capturedOnStreamClosed.get().run();

        verify(invalidationCloseable).close();
    }

    @Test
    void sseStream_closedDuringStartSse_closesInvalidationSubscription(@Mock Closeable invalidationCloseable) {
        asUnchecked(() -> when(runtime.startSse(any(), any(), any())).thenAnswer(invocation -> {
            Runnable onStreamClosed = invocation.getArgument(2);
            onStreamClosed.run();
            return Closeable.noop();
        }));
        when(streamInvalidationSubscription.subscribe(any())).thenReturn(invalidationCloseable);

        sendGet("/ui/api/displayables/stream");

        verify(invalidationCloseable).close();
    }

    private String baseUrl() {
        return "http://localhost:" + server.listenPort();
    }

    private HttpResponse<String> sendGet(String path) {
        return getAsUnchecked(() -> httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    private HttpResponse<String> sendPost(String path) {
        return getAsUnchecked(() -> httpClient.send(
                HttpRequest.newBuilder()
                           .uri(URI.create(baseUrl() + path))
                           .POST(HttpRequest.BodyPublishers.ofString(""))
                           .header("Content-Type", "application/x-www-form-urlencoded")
                           .build(),
                HttpResponse.BodyHandlers.ofString()));
    }

    private HttpResponse<String> sendDelete(String path) {
        return getAsUnchecked(() -> httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl() + path)).DELETE().build(),
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
