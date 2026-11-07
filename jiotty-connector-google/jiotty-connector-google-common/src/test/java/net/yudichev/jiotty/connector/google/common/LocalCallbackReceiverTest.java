package net.yudichev.jiotty.connector.google.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static net.yudichev.jiotty.connector.google.common.LocalCallbackReceiver.CALLBACK_PATH;
import static net.yudichev.jiotty.connector.google.common.LocalCallbackReceiver.bindAddress;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalCallbackReceiverTest {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private LocalCallbackReceiver receiver;

    @AfterEach
    void tearDown() {
        if (receiver != null) {
            receiver.stop();
        }
        httpClient.close();
    }

    @Test
    void listensOnLoopbackOnlyForLocalhost() throws Exception {
        receiver = new LocalCallbackReceiver("localhost", 0);
        var redirectUri = receiver.getRedirectUri();

        assertThat(receiver.boundAddress().getAddress().isLoopbackAddress(), is(true));
        assertThat(redirectUri, is("http://localhost:" + receiver.boundAddress().getPort() + CALLBACK_PATH));
    }

    @Test
    void listensOnAllInterfacesForOtherHosts() throws Exception {
        assertThat(bindAddress("my-host", 8888).getAddress().isAnyLocalAddress(), is(true));
        assertThat(bindAddress("LOCALHOST", 8888).getAddress().isLoopbackAddress(), is(true));
        assertThat(bindAddress("127.0.0.1", 8888).getAddress().isLoopbackAddress(), is(true));
    }

    @Test
    void returnsTheCodeFromTheCallback() throws Exception {
        receiver = new LocalCallbackReceiver("localhost", 0);
        receiver.getRedirectUri();

        var response = callback("?state=xyz&code=4%2F0Abc-def");

        assertThat(response.statusCode(), is(200));
        assertThat(response.body(), containsString("You may now close this window"));
        assertThat(receiver.waitForCode(), is("4/0Abc-def"));
    }

    @Test
    void failsWhenTheCallbackReportsAnError() throws Exception {
        receiver = new LocalCallbackReceiver("localhost", 0);
        receiver.getRedirectUri();

        callback("?error=access_denied");

        var exception = assertThrows(IOException.class, receiver::waitForCode);
        assertThat(exception.getMessage(), containsString("access_denied"));
    }

    private HttpResponse<String> callback(String query) throws IOException, InterruptedException {
        var uri = URI.create("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ':' + receiver.boundAddress().getPort() + CALLBACK_PATH + query);
        return httpClient.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
    }
}
