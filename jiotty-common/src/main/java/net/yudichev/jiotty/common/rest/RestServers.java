package net.yudichev.jiotty.common.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Json;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static java.util.concurrent.TimeUnit.MINUTES;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;

public final class RestServers {
    private static final Logger logger = LogManager.getLogger(RestServers.class);
    private static final ObjectWriter SUCCESS_WRITER = Json.createWriterFor(SuccessBody.class);
    private static final ObjectWriter ERROR_WRITER = Json.createWriterFor(ErrorBody.class);

    private RestServers() {
    }

    /// Renders `handler`'s outcome as the JSON body of `response`, awaiting it for up to three minutes. A failure sets
    /// [HttpStatuses#INTERNAL_SERVER_ERROR_500] on `response` and leaves the cause in the log, so the body carries the constant `INTERNAL_ERROR`.
    ///
    /// @param handlerName the name a failure's log line carries
    /// @return the JSON body to send
    public static String withErrorsHandledJson(String handlerName, HttpServletResponse response, CompletableFuture<?> handler) {
        checkNotNull(handlerName);
        checkNotNull(response);
        checkNotNull(handler);
        checkArgument(!handlerName.isBlank(), "handlerName must not be blank");
        addJsonContentType(response);
        try {
            Object result = getAsUnchecked(() -> handler.get(3, MINUTES));
            return write(SUCCESS_WRITER, new SuccessBody("true", result == null ? null : result.toString()));
        } catch (RuntimeException e) {
            logger.error("Failed to execute REST handler {}", handlerName, e);
            // Opaque error to the caller — the full cause is in the log above, never echoed to the client (no internal-detail leak).
            response.setStatus(INTERNAL_SERVER_ERROR_500);
            return createErrorJson("INTERNAL_ERROR");
        }
    }

    /// Rejects a request the caller got wrong, setting [HttpStatuses#BAD_REQUEST_400] on `response`. [#withErrorsHandledJson] is the path for an internal
    /// failure.
    ///
    /// @param errorText the reason, which reaches the client verbatim, so it describes the request the caller sent. Be careful not to put any personal info
    ///                  here
    /// @return the JSON body to send
    public static String createBadRequestJson(HttpServletResponse response, String errorText) {
        checkNotNull(response);
        checkNotNull(errorText);
        checkArgument(!errorText.isBlank(), "errorText must not be blank");
        addJsonContentType(response);
        response.setStatus(BAD_REQUEST_400);
        return createErrorJson(errorText);
    }

    private static void addJsonContentType(HttpServletResponse response) {
        response.addHeader(CONTENT_TYPE, CONTENT_TYPE_JSON);
    }

    private static String createErrorJson(String errorText) {
        return write(ERROR_WRITER, new ErrorBody("false", errorText));
    }

    /// Buffers rather than streaming into the response, because the value returned is what a Javalin handler hands to `context.result(String)`.
    private static String write(ObjectWriter writer, Object body) {
        var json = new StringBuilder(64);
        Json.writeTo(json, writer, body);
        return json.toString();
    }

    /// @param success  the string `true`, kept as the wire format an existing client parses
    /// @param response the handler's result rendered with `toString`, absent from the JSON when the handler produced none
    @JsonInclude(NON_NULL)
    private record SuccessBody(String success, @Nullable String response) {}

    /// @param success   the string `false`, kept as the wire format an existing client parses
    /// @param errorText what went wrong, shown to the client
    private record ErrorBody(String success, String errorText) {}
}
