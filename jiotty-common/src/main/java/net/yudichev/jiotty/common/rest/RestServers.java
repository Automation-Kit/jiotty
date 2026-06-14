package net.yudichev.jiotty.common.rest;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class RestServers {
    private static final Logger logger = LogManager.getLogger(RestServers.class);

    private RestServers() {
    }

    public static String withErrorsHandledJson(String handlerName, HttpServletResponse response, CompletableFuture<?> handler) {
        response.addHeader("Content-Type", "application/json");
        return withErrorsHandled(handlerName,
                                 handler,
                                 responseObj -> {
                                     ObjectNode factory = Json.object().put("success", "true");
                                     responseObj.ifPresent(theResponse -> factory.put("response", theResponse.toString()));
                                     return factory.toString();
                                 },
                                 message -> Json.object()
                                                .put("success", "false")
                                                .put("errorText", message)
                                                .toString());
    }

    private static String withErrorsHandled(String handlerName,
                                            CompletableFuture<?> handler,
                                            Function<Optional<Object>, String> successFactory,
                                            Function<String, String> errorFactory) {
        try {
            Object response = MoreThrowables.getAsUnchecked(() -> handler.get(3, TimeUnit.MINUTES));
            return successFactory.apply(Optional.ofNullable(response));
        } catch (RuntimeException e) {
            logger.error("Failed to execute REST handler {}", handlerName, e);
            // Opaque error to the caller — the full cause is in the log above, never echoed to the client (no internal-detail leak).
            return errorFactory.apply("INTERNAL_ERROR");
        }
    }
}
