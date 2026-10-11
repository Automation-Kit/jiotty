package net.yudichev.jiotty.common.rest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static org.assertj.core.api.Assertions.assertThat;

class HttpResponseExceptionTest {
    @Test
    void findsTheHttpResponseWrappedInTheCausalChain() {
        var httpResponse = new HttpResponseException(SERVICE_UNAVAILABLE_503, "down");

        assertThat(HttpResponseException.findIn(new CompletionException(new RuntimeException("call failed", httpResponse)))).containsSame(httpResponse);
    }

    @Test
    void findsTheHttpResponseItself() {
        var httpResponse = new HttpResponseException(NOT_FOUND_404, "");

        assertThat(HttpResponseException.findIn(httpResponse)).containsSame(httpResponse);
    }

    @Test
    void aFailureWithoutAnHttpResponseFindsNothing() {
        assertThat(HttpResponseException.findIn(new RuntimeException(new IOException("connection reset")))).isEmpty();
    }
}
