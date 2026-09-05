package net.yudichev.jiotty.adminalerts.http;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.adminalerts.AdminAlertService.ResolveByIdOutcome;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.BaseHttpServlet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Serial;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.rest.HttpStatuses.CONFLICT_409;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;
import static net.yudichev.jiotty.common.rest.HttpStatuses.PAYLOAD_TOO_LARGE_413;

/// Handles `POST /admin/api/alerts/{id}/resolve`. Authorisation is performed by [AdminBearerAuthFilter] earlier in the chain; the audit identity is read from
/// the request attribute the filter set.
///
/// Implemented as an async servlet ([HttpServletRequest#startAsync()]) so the Jetty request thread is released while the alert service does its database work.
public final class AdminAlertResolveServlet extends BaseHttpServlet {
    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger logger = LogManager.getLogger(AdminAlertResolveServlet.class);

    private static final String RESOLVE_SUFFIX = "/resolve";
    private static final long ASYNC_TIMEOUT_MS = 30_000;
    /// Hard cap on the resolve-note request body: at most this many bytes are read before parsing, so a huge or
    /// chunked body cannot be buffered whole (`Content-Length` alone is attacker-controllable).
    private static final int MAX_NOTE_BYTES = 8 * 1024;

    private final AdminAlertService alertService;

    @Inject
    public AdminAlertResolveServlet(@Dependency AdminAlertService alertService) {
        this.alertService = checkNotNull(alertService, "alertService");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || !pathInfo.startsWith("/") || !pathInfo.endsWith(RESOLVE_SUFFIX)) {
            writeJsonError(response, NOT_FOUND_404, "Unknown path");
            return;
        }
        String alertId = pathInfo.substring(1, pathInfo.length() - RESOLVE_SUFFIX.length());
        if (alertId.isBlank() || alertId.contains("/")) {
            writeJsonError(response, NOT_FOUND_404, "Unknown path");
            return;
        }
        byte[] bodyBytes = request.getInputStream().readNBytes(MAX_NOTE_BYTES + 1);
        if (bodyBytes.length > MAX_NOTE_BYTES) {
            writeJsonError(response, PAYLOAD_TOO_LARGE_413, "Request body too large");
            return;
        }
        String resolvedByHeader = (String) request.getAttribute(AdminBearerAuthFilter.GRAFANA_USER_REQUEST_ATTRIBUTE);
        String resolvedBy = resolvedByHeader == null ? AdminBearerAuthFilter.DEFAULT_GRAFANA_USER : resolvedByHeader;
        Optional<String> note = parseNote(bodyBytes);

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(ASYNC_TIMEOUT_MS);

        alertService.resolveById(alertId, resolvedBy, note)
                    .whenComplete((outcome, error) -> {
                        if (error != null) {
                            writeError(asyncContext, alertId, error);
                        } else {
                            writeOutcome(asyncContext, alertId, outcome);
                        }
                    });
    }

    private static void writeError(AsyncContext asyncContext, String alertId, Throwable error) {
        logger.info("Resolve failed for alert {}", alertId, error);
        var response = (HttpServletResponse) asyncContext.getResponse();
        try {
            writeJsonError(response, INTERNAL_SERVER_ERROR_500, "Resolve failed");
        } catch (IOException e) {
            logger.info("Failed to write error response for alert {}", alertId, e);
        } finally {
            asyncContext.complete();
        }
    }

    private static void writeOutcome(AsyncContext asyncContext, String alertId, ResolveByIdOutcome outcome) {
        var response = (HttpServletResponse) asyncContext.getResponse();
        try {
            switch (outcome) {
                case RESOLVED -> response.setStatus(NO_CONTENT_204);
                case ALREADY_RESOLVED -> writeJsonError(response, CONFLICT_409, "Already resolved");
                case UNKNOWN -> writeJsonError(response, NOT_FOUND_404, "Unknown alert");
            }
        } catch (IOException e) {
            logger.info("Failed to write response for alert {}", alertId, e);
        } finally {
            asyncContext.complete();
        }
    }

    private static Optional<String> parseNote(byte[] bodyBytes) {
        if (bodyBytes.length == 0) {
            return Optional.empty();
        }
        var parsed = Json.parse(bodyBytes, ResolveBody.class);
        if (parsed.note() == null || parsed.note().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parsed.note());
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        response.getWriter().print(Json.stringify(new ErrorBody(message)));
    }

    private record ResolveBody(@Nullable String note) {
    }

    private record ErrorBody(String error) {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }
}
