package net.yudichev.jiotty.common.rest;

import com.google.inject.Guice;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static net.yudichev.jiotty.common.inject.BindingSpec.literally;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static org.assertj.core.api.Assertions.assertThat;

class RestServerModuleTest {
    private OkHttpClient client;
    private @Nullable JavalinRestServer server;

    @BeforeEach
    void setUp() {
        client = RestClients.newClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (client != null) {
            RestClients.shutdown(client);
        }
    }

    @Test
    void serves() throws Exception {
        startServer(RestServerModule.builder());

        assertThat(get("/test")).isEqualTo("hello");
    }

    /// A server given an address binds that one alone, so a caller naming a loopback address gets a server the rest of the network cannot reach.
    @Test
    void servesOnTheConfiguredHost() throws Exception {
        startServer(RestServerModule.builder().withListenHost(literally("127.0.0.1")));

        assertThat(get("/test")).isEqualTo("hello");
    }

    private void startServer(RestServerModule.Builder builder) {
        var restServer = (JavalinRestServer) Guice.createInjector(builder.build()).getInstance(RestServer.class);
        restServer.get("/test", ctx -> ctx.result("hello"));
        restServer.start();
        server = restServer;
    }

    private String get(String path) throws Exception {
        assert server != null : "every test starts a server before requesting from it";
        try (Response response = client.newCall(
                new Request.Builder().url("http://127.0.0.1:" + server.port() + path).get().build()).execute()) {
            assertThat(response.code()).isEqualTo(OK_200);
            return response.body().string();
        }
    }
}
