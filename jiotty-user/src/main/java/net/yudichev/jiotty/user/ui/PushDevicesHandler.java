package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.push.PushDeviceRecord;
import net.yudichev.jiotty.user.push.PushDeviceRegisterRequest;
import net.yudichev.jiotty.user.push.PushDeviceStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;

/// Handles the `/ui/api/push/devices*` surface:
/// - `POST /ui/api/push/devices` — register the calling device.
/// - `DELETE /ui/api/push/devices/{deviceId}` — unregister the named device.
public final class PushDevicesHandler extends BaseLifecycleComponent implements ApiPathHandler {
    static final String PATH = "/push/devices";
    private static final String UNREGISTER_PREFIX = PATH + "/";
    private static final Logger logger = LogManager.getLogger(PushDevicesHandler.class);
    private static final ObjectReader REQUEST_READER = UIJson.MAPPER.readerFor(PushDeviceRegisterRequest.class);

    private final PushDeviceStore pushDeviceStore;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Provider<SchedulingExecutor> executorProvider;

    private SchedulingExecutor executor;

    @Inject
    public PushDevicesHandler(PushDeviceStore pushDeviceStore,
                              CurrentDateTimeProvider currentDateTimeProvider,
                              @UIExecutor Provider<SchedulingExecutor> executorProvider) {
        this.pushDeviceStore = checkNotNull(pushDeviceStore, "pushDeviceStore");
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider, "currentDateTimeProvider");
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
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        if (pathInfo != null && pathInfo.startsWith(UNREGISTER_PREFIX) && pathInfo.length() > UNREGISTER_PREFIX.length()) {
            if ("DELETE".equals(method)) {
                handleUnregister(pathInfo.substring(UNREGISTER_PREFIX.length()), request, response);
                return;
            }
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        ApiServlet.writeUnknownPath(response);
    }

    private void handleRegister(HttpServletRequest request, HttpServletResponse response) {
        whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
            AsyncContext asyncContext = request.startAsync();
            PushDeviceRegisterRequest body;
            try {
                body = REQUEST_READER.readValue(request.getReader());
            } catch (IOException e) {
                writeJsonError(response, 400, "Invalid JSON body: " + humanReadableMessage(e));
                asyncContext.complete();
                return;
            }
            var builder = PushDeviceRecord.builder()
                                          .setDeviceId(body.deviceId())
                                          .setToken(body.token())
                                          .setRegisteredAt(currentDateTimeProvider.currentInstant());
            body.platform().ifPresent(builder::setPlatform);
            body.appVersion().ifPresent(builder::setAppVersion);
            pushDeviceStore.upsert(builder.build())
                           .whenCompleteAsync((_, throwable) -> completeResponse(asyncContext, response, throwable), executor);
        }));
    }

    private void handleUnregister(String deviceId, HttpServletRequest request, HttpServletResponse response) {
        whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
            AsyncContext asyncContext = request.startAsync();
            pushDeviceStore.remove(deviceId)
                           .whenCompleteAsync((_, throwable) -> completeResponse(asyncContext, response, throwable), executor);
        }));
    }

    private static void completeResponse(AsyncContext asyncContext, HttpServletResponse response, @Nullable Throwable throwable) {
        try {
            if (throwable != null) {
                logger.info("Push device request processing failed", throwable);
                asUnchecked(() -> writeJsonError(response, 500, humanReadableMessage(throwable)));
            } else {
                response.setStatus(204);
            }
        } finally {
            asyncContext.complete();
        }
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        UIJson.WRITER.writeValue(response.getWriter(), Map.of("error", message));
    }
}
