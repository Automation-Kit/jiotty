package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.ObjectReader;
import com.google.common.annotations.VisibleForTesting;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.push.PushDeviceRecord;
import net.yudichev.jiotty.user.push.PushDeviceRegisterRequest;
import net.yudichev.jiotty.user.push.PushDeviceStore;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.ReceivedBody;
import net.yudichev.jiotty.user.ui.io.RequestBodies;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NO_CONTENT_204;
import static net.yudichev.jiotty.common.rest.HttpStatuses.PAYLOAD_TOO_LARGE_413;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;
import static net.yudichev.jiotty.user.ui.UIServerModule.Dependency;
import static net.yudichev.jiotty.user.ui.UIServerModule.SubjectId;

/// Handles the `/ui/api/push/devices*` surface:
/// - `POST /ui/api/push/devices` — register the calling device.
/// - `DELETE /ui/api/push/devices/{deviceId}` — unregister the named device.
public final class PushDevicesHandler extends BaseLifecycleComponent implements ApiPathHandler {
    static final String PATH = "/push/devices";
    @VisibleForTesting
    static final String REQUEST_FAILED_ALERT_TITLE = "Push device request failed";
    @VisibleForTesting
    static final String TIMED_OUT_ALERT_TITLE = "Push device request timed out";
    private static final String UNREGISTER_PREFIX = PATH + "/";
    private static final Logger logger = LogManager.getLogger(PushDevicesHandler.class);
    private static final ObjectReader REQUEST_READER = UIJson.MAPPER.readerFor(PushDeviceRegisterRequest.class);
    /// Hard cap on the register request body: at most this many bytes are read before parsing, bounding the
    /// buffer a malicious client can force the server to hold.
    private static final int MAX_BODY_BYTES = 32 * 1024;
    private static final Answer ACKNOWLEDGEMENT = Answer.of(NO_CONTENT_204);
    private static final Answer INVALID_BODY_ANSWER = ErrorResponse.createAnswer(BAD_REQUEST_400, ErrorResponse.INVALID_BODY);
    private static final Answer BODY_TOO_LARGE_ANSWER = ErrorResponse.createAnswer(PAYLOAD_TOO_LARGE_413, ErrorResponse.BODY_TOO_LARGE);

    private final PushDeviceStore pushDeviceStore;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final AdminAlertService alertService;
    private final Consumer<Throwable> encodingFailureHandler;
    private final Deadline deadline;
    private final Provider<SchedulingExecutor> executorProvider;
    private final String userId;

    private SchedulingExecutor executor;

    @Inject
    public PushDevicesHandler(PushDeviceStore pushDeviceStore,
                              CurrentDateTimeProvider currentDateTimeProvider,
                              @Dependency AdminAlertService alertService,
                              @UIExecutor Provider<SchedulingExecutor> executorProvider,
                              @SubjectId String userId) {
        this.pushDeviceStore = checkNotNull(pushDeviceStore, "pushDeviceStore");
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider, "currentDateTimeProvider");
        this.alertService = checkNotNull(alertService, "alertService");
        this.userId = checkNotNull(userId, "userId");
        encodingFailureHandler = failure -> alertService.raise(WARNING, "Push device answer could not be encoded", logger, failure);
        deadline = HandlerDeadlines.create(alertService, logger, userId, TIMED_OUT_ALERT_TITLE, encodingFailureHandler);
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
    }

    @Override
    public String pathPrefix() {
        return PATH;
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) {
        String pathInfo = request.getPathInfo();
        String method = request.getMethod();
        if (PATH.equals(pathInfo)) {
            if ("POST".equals(method)) {
                handleRegister(request, response);
                return;
            }
            response.setStatus(METHOD_NOT_ALLOWED_405);
            return;
        }
        if (pathInfo != null && pathInfo.startsWith(UNREGISTER_PREFIX) && pathInfo.length() > UNREGISTER_PREFIX.length()) {
            if ("DELETE".equals(method)) {
                handleUnregister(pathInfo.substring(UNREGISTER_PREFIX.length()), request, response);
                return;
            }
            response.setStatus(METHOD_NOT_ALLOWED_405);
            return;
        }
        ApiServlet.writeUnknownPath(response);
    }

    private void handleRegister(HttpServletRequest request, HttpServletResponse response) {
        whenStartedAndNotLifecycling(() -> {
            var exchange = AsyncExchange.start(alertService, request, response, deadline);
            RequestBodies.<PushDeviceRegisterRequest>read(exchange, MAX_BODY_BYTES, REQUEST_READER)
                         .whenCompleteAsync((received, throwable) -> {
                             try {
                                 if (throwable != null) {
                                     logger.debug("[{}] Could not read a push device registration", userId, throwable);
                                     answer(exchange, INVALID_BODY_ANSWER);
                                 } else {
                                     switch (received) {
                                         case ReceivedBody.Parsed(PushDeviceRegisterRequest body) -> register(exchange, body);
                                         case ReceivedBody.Empty<PushDeviceRegisterRequest> _ -> answer(exchange, INVALID_BODY_ANSWER);
                                         case ReceivedBody.TooLarge<PushDeviceRegisterRequest> _ -> answer(exchange, BODY_TOO_LARGE_ANSWER);
                                         case ReceivedBody.Malformed(IOException cause) -> {
                                             if (logger.isDebugEnabled()) {
                                                 logger.debug("[{}] Malformed push device registration: {}", userId, humanReadableMessageFormattable(cause));
                                             }
                                             answer(exchange, INVALID_BODY_ANSWER);
                                         }
                                     }
                                 }
                             } catch (RuntimeException e) {
                                 // pushDeviceStore throws while it is stopping; completeResponse writes a 500 and raises an admin alert.
                                 completeResponse(exchange, e);
                             }
                         }, executor);
            return null;
        });
    }

    private void register(AsyncExchange exchange, PushDeviceRegisterRequest body) {
        var builder = PushDeviceRecord.builder()
                                      .setDeviceId(body.deviceId())
                                      .setToken(body.token())
                                      .setRegisteredAt(currentDateTimeProvider.currentInstant());
        body.platform().ifPresent(builder::setPlatform);
        body.appVersion().ifPresent(builder::setAppVersion);
        pushDeviceStore.upsert(builder.build())
                       .whenCompleteAsync((_, throwable) -> completeResponse(exchange, throwable), executor);
    }

    private void handleUnregister(String deviceId, HttpServletRequest request, HttpServletResponse response) {
        whenStartedAndNotLifecycling(() -> {
            var exchange = AsyncExchange.start(alertService, request, response, deadline);
            pushDeviceStore.remove(deviceId)
                           .whenCompleteAsync((_, throwable) -> completeResponse(exchange, throwable), executor);
        });
    }

    private void completeResponse(AsyncExchange exchange, @Nullable Throwable throwable) {
        if (throwable == null) {
            answer(exchange, ACKNOWLEDGEMENT);
        } else {
            alertService.raise(WARNING, REQUEST_FAILED_ALERT_TITLE, logger, throwable);
            answer(exchange, ErrorResponse.SERVER_FAULT);
        }
    }

    private void answer(AsyncExchange exchange, Answer answer) {
        exchange.answer(answer, encodingFailureHandler);
    }
}
