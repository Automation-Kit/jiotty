package net.yudichev.jiotty.user.ui;

import com.google.common.annotations.VisibleForTesting;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.TextBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.user.ui.UIServerModule.Dependency;
import static net.yudichev.jiotty.user.ui.UIServerModule.SubjectId;

/// Handles `GET /ui/api/displayables/download?displayableId=...&downloadId=...` — serves a downloadable artefact emitted by a displayable.
public final class DisplayableDownloadHandler extends BaseLifecycleComponent implements ApiPathHandler {
    static final String PATH = "/displayables/download";
    @VisibleForTesting
    static final String TIMED_OUT_ALERT_TITLE = "Displayable download timed out";
    @VisibleForTesting
    static final String DOWNLOAD_FAILED_ALERT_TITLE = "Displayable download failed";
    @VisibleForTesting
    static final String DISPLAYABLE_ID_PARAMETER = "displayableId";
    @VisibleForTesting
    static final String DOWNLOAD_ID_PARAMETER = "downloadId";
    /// The start of the 404 body for an unknown displayable id; the id and a closing quote follow it.
    @VisibleForTesting
    static final String UNKNOWN_DISPLAYABLE_MESSAGE_PREFIX = "No displayable found with id='";
    @VisibleForTesting
    static final String MISSING_DOWNLOAD_ID_MESSAGE = "Missing '" + DOWNLOAD_ID_PARAMETER + "' parameter";

    private static final Logger logger = LogManager.getLogger(DisplayableDownloadHandler.class);

    private static final Answer MISSING_DOWNLOAD_ID_ANSWER = Answer.of(NOT_FOUND_404, new TextBody(MISSING_DOWNLOAD_ID_MESSAGE));

    private final DisplayableRegistry registry;
    private final AdminAlertService alertService;
    private final String userId;
    private final Consumer<Throwable> encodingFailureHandler;
    private final Deadline deadline;

    @Inject
    public DisplayableDownloadHandler(DisplayableRegistry registry, @Dependency AdminAlertService alertService, @SubjectId String userId) {
        this.registry = checkNotNull(registry, "registry");
        this.alertService = checkNotNull(alertService, "alertService");
        this.userId = checkNotNull(userId, "userId");
        encodingFailureHandler = failure -> alertService.raise(WARNING, "Download answer could not be encoded", logger, failure);
        deadline = HandlerDeadlines.create(alertService, logger, userId, TIMED_OUT_ALERT_TITLE, encodingFailureHandler);
    }

    @Override
    public String pathPrefix() {
        return PATH;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) {
        if (!PATH.equals(request.getPathInfo())) {
            ApiServlet.writeUnknownPath(response);
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setStatus(METHOD_NOT_ALLOWED_405);
            return;
        }
        // Read while dispatching: once the exchange has started, only the holder of its claim may touch the request.
        String displayableId = request.getParameter(DISPLAYABLE_ID_PARAMETER);
        String downloadId = request.getParameter(DOWNLOAD_ID_PARAMETER);
        whenStartedAndNotLifecycling(() -> {
            var exchange = AsyncExchange.start(alertService, request, response, deadline);
            exchange.runOnContainerThread(() -> whenStartedAndNotLifecycling(() -> {
                try {
                    serve(exchange, displayableId, downloadId);
                } catch (RuntimeException e) {
                    alertService.raise(WARNING, DOWNLOAD_FAILED_ALERT_TITLE, logger, '[' + userId + "] Serving a download", e);
                    answer(exchange, ErrorResponse.SERVER_FAULT);
                }
            }));
        });
    }

    /// @param displayableId the displayable the request names; `null` when it names none
    /// @param downloadId    the download the request names; `null` when it names none
    private void serve(AsyncExchange exchange, @Nullable String displayableId, @Nullable String downloadId) {
        Displayable displayable = displayableId == null ? null : registry.find(displayableId).orElse(null);
        if (displayable == null) {
            answer(exchange, Answer.of(NOT_FOUND_404, new TextBody(UNKNOWN_DISPLAYABLE_MESSAGE_PREFIX + displayableId + '\'')));
        } else if (downloadId == null) {
            answer(exchange, MISSING_DOWNLOAD_ID_ANSWER);
        } else {
            displayable.handleDownload(downloadId).whenComplete((downloadAnswer, throwable) -> {
                if (throwable == null) {
                    answer(exchange, downloadAnswer);
                } else {
                    logger.debug("[{}] Displayable {} download {} failed", userId, displayableId, downloadId, throwable);
                    // TODO answer with a fixed error code and a 5xx, and raise an admin alert: the failure's message can carry internal detail,
                    //  and a failed download is a server fault.
                    answer(exchange, Answer.of(BAD_REQUEST_400, new TextBody(humanReadableMessage(throwable))));
                }
            });
        }
    }

    private void answer(AsyncExchange exchange, Answer answer) {
        exchange.answer(answer, encodingFailureHandler);
    }
}
