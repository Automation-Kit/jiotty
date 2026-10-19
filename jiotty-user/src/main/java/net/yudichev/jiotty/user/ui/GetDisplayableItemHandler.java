package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.JsonBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;
import static net.yudichev.jiotty.user.ui.UIServerModule.Dependency;
import static net.yudichev.jiotty.user.ui.UIServerModule.SubjectId;

/// Handles `GET /ui/api/displayables/item?id=...` — returns the named displayable's DTO.
public final class GetDisplayableItemHandler extends BaseLifecycleComponent implements ApiPathHandler {
    static final String PATH = "/displayables/item";
    @VisibleForTesting
    static final String ID_PARAMETER = "id";
    @VisibleForTesting
    static final String DTO_FAILED_ALERT_TITLE = "Displayable DTO generation failed";
    @VisibleForTesting
    static final String ENCODING_FAILED_ALERT_TITLE = "Displayable DTO could not be serialised";
    @VisibleForTesting
    static final String TIMED_OUT_ALERT_TITLE = "Displayable item request timed out";
    private static final Logger logger = LogManager.getLogger(GetDisplayableItemHandler.class);

    private static final ObjectWriter ITEM_WRITER = UIJson.createWriterFor(ItemResponse.class);

    private final DisplayableRegistry registry;
    private final AdminAlertService alertService;
    private final Consumer<Throwable> encodingFailureHandler;
    private final Deadline deadline;
    private final Provider<SchedulingExecutor> executorProvider;

    private SchedulingExecutor executor;

    @Inject
    public GetDisplayableItemHandler(DisplayableRegistry registry,
                                     @Dependency AdminAlertService alertService,
                                     @UIExecutor Provider<SchedulingExecutor> executorProvider,
                                     @SubjectId String userId) {
        this.registry = checkNotNull(registry, "registry");
        this.alertService = checkNotNull(alertService, "alertService");
        encodingFailureHandler = failure -> alertService.raise(WARNING, ENCODING_FAILED_ALERT_TITLE, logger, failure);
        deadline = HandlerDeadlines.create(alertService, logger, checkNotNull(userId, "userId"), TIMED_OUT_ALERT_TITLE, encodingFailureHandler);
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
        if (!PATH.equals(request.getPathInfo())) {
            ApiServlet.writeUnknownPath(response);
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setStatus(METHOD_NOT_ALLOWED_405);
            return;
        }
        whenStartedAndNotLifecycling(() -> {
            String id = request.getParameter(ID_PARAMETER);
            if (id == null || id.isBlank()) {
                ErrorResponse.write(response, BAD_REQUEST_400, ErrorResponse.MISSING_ID);
                return null;
            }
            Displayable displayable = registry.find(id).orElse(null);
            if (displayable == null) {
                ErrorResponse.write(response, NOT_FOUND_404, ErrorResponse.UNKNOWN_ID);
                return null;
            }
            var exchange = AsyncExchange.start(alertService, request, response, deadline);
            displayable.toDto()
                       .whenCompleteAsync((dto, throwable) -> {
                           Answer answer;
                           if (throwable == null) {
                               answer = Answer.of(OK_200, new JsonBody(ITEM_WRITER, new ItemResponse(id, dto)));
                           } else {
                               alertService.raise(WARNING, DTO_FAILED_ALERT_TITLE, logger, throwable);
                               answer = ErrorResponse.SERVER_FAULT;
                           }
                           // A DTO of ours that will not serialise is a bug, not a client error, so it raises an alert.
                           exchange.answer(answer, encodingFailureHandler);
                       }, executor);
            return null;
        });
    }

    private record ItemResponse(String id, DisplayableDto dto) {}
}
