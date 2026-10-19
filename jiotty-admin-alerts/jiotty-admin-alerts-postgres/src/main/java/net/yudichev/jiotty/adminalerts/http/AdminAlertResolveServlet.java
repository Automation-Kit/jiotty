package net.yudichev.jiotty.adminalerts.http;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.user.ui.BaseHttpServlet;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.JsonBody;
import net.yudichev.jiotty.user.ui.io.ReceivedBody;
import net.yudichev.jiotty.user.ui.io.RequestBodies;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Serial;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.rest.ContentTypes.CHARSET_UTF_8;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
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
    @VisibleForTesting
    static final String RESOLVE_SUFFIX = "/resolve";
    @Serial
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LogManager.getLogger(AdminAlertResolveServlet.class);
    /// How long a resolve may take before it is answered as failed.
    private static final Duration ANSWER_DEADLINE = Duration.ofSeconds(30);
    /// Hard cap on the resolve-note request body: at most this many bytes are read before parsing, so a huge or
    /// chunked body cannot be buffered whole (`Content-Length` alone is attacker-controllable).
    private static final int MAX_NOTE_BYTES = 8 * 1024;
    private static final ObjectReader RESOLVE_BODY_READER = Json.createReaderFor(ResolveBody.class);
    private static final ObjectWriter ERROR_WRITER = Json.createWriterFor(ErrorBody.class);
    private static final Answer RESOLVED_ANSWER = Answer.of(NO_CONTENT_204);
    private static final Answer ALREADY_RESOLVED_ANSWER = createErrorAnswer(CONFLICT_409, "Already resolved");
    private static final Answer UNKNOWN_ALERT_ANSWER = createErrorAnswer(NOT_FOUND_404, "Unknown alert");
    private static final Answer RESOLVE_FAILURE_ANSWER = createErrorAnswer(INTERNAL_SERVER_ERROR_500, "Resolve failed");
    private static final Answer BODY_TOO_LARGE_ANSWER = createErrorAnswer(PAYLOAD_TOO_LARGE_413, "Request body too large");
    private static final Answer INVALID_BODY_ANSWER = createErrorAnswer(BAD_REQUEST_400, "Invalid request body");
    private static final ErrorBody UNKNOWN_PATH_BODY = new ErrorBody("Unknown path");

    private final AdminAlertService alertService;
    private final Consumer<Throwable> encodingFailureHandler;
    private final Deadline deadline;

    @Inject
    public AdminAlertResolveServlet(@Dependency AdminAlertService alertService) {
        this.alertService = checkNotNull(alertService, "alertService");
        encodingFailureHandler = failure -> alertService.raise(WARNING, "Alert resolve response could not be serialised", logger, failure);
        // Logged, not alerted: the alert service is what has stalled.
        deadline = Deadline.after(ANSWER_DEADLINE, RESOLVE_FAILURE_ANSWER, encodingFailureHandler, stall -> {
            if (stall.serverStalled()) {
                logger.info("A resolve took longer than {}", ANSWER_DEADLINE);
            } else {
                logger.debug("A resolve request body was still arriving after {}", ANSWER_DEADLINE);
            }
        });
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || !pathInfo.startsWith("/") || !pathInfo.endsWith(RESOLVE_SUFFIX)) {
            writeUnknownPath(response);
            return;
        }
        String alertId = pathInfo.substring(1, pathInfo.length() - RESOLVE_SUFFIX.length());
        if (alertId.isBlank() || alertId.contains("/")) {
            writeUnknownPath(response);
            return;
        }
        String resolvedByHeader = (String) request.getAttribute(AdminBearerAuthFilter.GRAFANA_USER_REQUEST_ATTRIBUTE);
        String resolvedBy = resolvedByHeader == null ? AdminBearerAuthFilter.DEFAULT_GRAFANA_USER : resolvedByHeader;

        var exchange = AsyncExchange.start(alertService, request, response, deadline);
        RequestBodies.<ResolveBody>read(exchange, MAX_NOTE_BYTES, RESOLVE_BODY_READER).whenComplete((received, readFailure) -> {
            if (readFailure != null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Could not read the resolve request for alert {}: {}", alertId, humanReadableMessageFormattable(readFailure));
                }
                answer(exchange, INVALID_BODY_ANSWER);
            } else {
                switch (received) {
                    case ReceivedBody.Parsed(ResolveBody body) -> resolve(exchange, alertId, resolvedBy, noteOf(body));
                    case ReceivedBody.Empty<ResolveBody> _ -> resolve(exchange, alertId, resolvedBy, Optional.empty());
                    case ReceivedBody.TooLarge<ResolveBody> _ -> answer(exchange, BODY_TOO_LARGE_ANSWER);
                    case ReceivedBody.Malformed(IOException cause) -> {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Malformed resolve request for alert {}: {}", alertId, humanReadableMessageFormattable(cause));
                        }
                        answer(exchange, INVALID_BODY_ANSWER);
                    }
                }
            }
        });
    }

    private void resolve(AsyncExchange exchange, String alertId, String resolvedBy, Optional<String> note) {
        CompletableFuture<AdminAlertService.ResolveByIdOutcome> resolution;
        try {
            resolution = alertService.resolveById(alertId, resolvedBy, note);
        } catch (RuntimeException e) {
            // The service throws while it is stopping and on arguments it refuses; both are answered as a failed resolve.
            resolution = failedFuture(e);
        }
        resolution.whenComplete((outcome, error) -> {
            if (error == null) {
                answer(exchange, switch (outcome) {
                    case RESOLVED -> RESOLVED_ANSWER;
                    case ALREADY_RESOLVED -> ALREADY_RESOLVED_ANSWER;
                    case UNKNOWN -> UNKNOWN_ALERT_ANSWER;
                });
            } else {
                logger.info("Resolve failed for alert {}", alertId, error);
                answer(exchange, RESOLVE_FAILURE_ANSWER);
            }
        });
    }

    private void answer(AsyncExchange exchange, Answer answer) {
        exchange.answer(answer, encodingFailureHandler);
    }

    private static Optional<String> noteOf(ResolveBody body) {
        String note = body.note();
        return note == null || note.isBlank() ? Optional.empty() : Optional.of(note);
    }

    private static void writeUnknownPath(HttpServletResponse response) throws IOException {
        response.setStatus(NOT_FOUND_404);
        response.setCharacterEncoding(CHARSET_UTF_8);
        response.setContentType(CONTENT_TYPE_JSON);
        ERROR_WRITER.writeValue(response.getOutputStream(), UNKNOWN_PATH_BODY);
    }

    private static Answer createErrorAnswer(int status, String message) {
        return Answer.of(status, new JsonBody(ERROR_WRITER, new ErrorBody(message)));
    }

    /// @param note what the operator wrote about the resolution, `null` when the body names none
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
