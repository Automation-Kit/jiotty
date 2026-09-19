package net.yudichev.jiotty.common.rest;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.CompletableFutures.failure;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RestServersTest {
    @Mock
    private HttpServletResponse response;

    static Stream<Arguments> rendersTheHandlerResult() {
        return Stream.of(arguments(completedFuture("done"), "{\"success\":\"true\",\"response\":\"done\"}"),
                         arguments(completedFuture(null), "{\"success\":\"true\"}"));
    }

    /// The bodies are asserted whole, because an external client parses this wire format.
    @ParameterizedTest
    @MethodSource
    void rendersTheHandlerResult(CompletableFuture<?> handler, String expectedBody) {
        assertThat(RestServers.withErrorsHandledJson("/test", response, handler)).isEqualTo(expectedBody);

        verify(response).addHeader(CONTENT_TYPE, CONTENT_TYPE_JSON);
        verifyNoMoreInteractions(response);
    }

    @Test
    void reportsAHandlerFailureAsAnOpaqueServerError() {
        assertThat(RestServers.withErrorsHandledJson("/test", response, failure(new RuntimeException("connector exploded"))))
                .isEqualTo("{\"success\":\"false\",\"errorText\":\"INTERNAL_ERROR\"}");

        verify(response).addHeader(CONTENT_TYPE, CONTENT_TYPE_JSON);
        verify(response).setStatus(INTERNAL_SERVER_ERROR_500);
    }

    @Test
    void rendersABadRequestWithItsReason() {
        assertThat(RestServers.createBadRequestJson(response, "pos is out of range"))
                .isEqualTo("{\"success\":\"false\",\"errorText\":\"pos is out of range\"}");

        verify(response).addHeader(CONTENT_TYPE, CONTENT_TYPE_JSON);
        verify(response).setStatus(BAD_REQUEST_400);
    }

    @Test
    void rejectsABlankReason() {
        assertThatThrownBy(() -> RestServers.createBadRequestJson(response, " ")).isInstanceOf(IllegalArgumentException.class);

        verifyNoMoreInteractions(response);
    }

    @Test
    void rejectsABlankHandlerName() {
        assertThatThrownBy(() -> RestServers.withErrorsHandledJson(" ", response, completedFuture("done"))).isInstanceOf(IllegalArgumentException.class);

        verifyNoMoreInteractions(response);
    }
}
