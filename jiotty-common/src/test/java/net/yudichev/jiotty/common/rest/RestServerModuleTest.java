package net.yudichev.jiotty.common.rest;

import com.google.inject.Guice;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RestServerModuleTest {
    private RestServer server;
    private OkHttpClient client;

    @BeforeEach
    void setUp() {
        var injector = Guice.createInjector(RestServerModule.builder().build());
        server = injector.getInstance(RestServer.class);
        server.get("/test", ctx -> ctx.result("hello"));
        ((JavalinRestServer) server).start();
        client = RestClients.newClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            ((JavalinRestServer) server).stop();
        }
        if (client != null) {
            RestClients.shutdown(client);
        }
    }

    @Test
    void getRequest() throws Exception {
        try (okhttp3.Response response = client.newCall(
                new Request.Builder().url("http://localhost:" + server.port() + "/test").get().build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("hello");
        }
    }
}
