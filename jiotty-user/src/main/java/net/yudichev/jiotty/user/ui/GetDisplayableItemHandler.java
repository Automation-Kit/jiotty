package net.yudichev.jiotty.user.ui;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;
import static net.yudichev.jiotty.user.ui.UIServerModule.Dependency;

/// Handles `GET /ui/api/displayables/item?id=...` — returns the named displayable's DTO.
public final class GetDisplayableItemHandler extends BaseLifecycleComponent implements ApiPathHandler {
    static final String PATH = "/displayables/item";
    private static final Logger logger = LogManager.getLogger(GetDisplayableItemHandler.class);

    private final DisplayableRegistry registry;
    private final AdminAlertService alertService;
    private final Provider<SchedulingExecutor> executorProvider;

    private SchedulingExecutor executor;

    @Inject
    public GetDisplayableItemHandler(DisplayableRegistry registry,
                                     @Dependency AdminAlertService alertService,
                                     @UIExecutor Provider<SchedulingExecutor> executorProvider) {
        this.registry = checkNotNull(registry, "registry");
        this.alertService = checkNotNull(alertService, "alertService");
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
            String id = request.getParameter("id");
            if (id == null || id.isBlank()) {
                writeJsonError(response, BAD_REQUEST_400, "missing id");
                return null;
            }
            Displayable displayable = registry.find(id).orElse(null);
            if (displayable == null) {
                writeJsonError(response, NOT_FOUND_404, "unknown id");
                return null;
            }
            AsyncContext asyncContext = request.startAsync();
            displayable.toDto()
                       .whenCompleteAsync((dto, throwable) -> {
                           try {
                               if (throwable != null) {
                                   alertService.raise(WARNING, "Displayable DTO generation failed", logger, throwable);
                                   writeJsonError(response, INTERNAL_SERVER_ERROR_500, "INTERNAL_ERROR");
                               } else {
                                   response.setCharacterEncoding("utf-8");
                                   response.setContentType("application/json");
                                   UIJson.WRITER.writeValue(response.getWriter(), Map.of("id", id, "dto", dto));
                               }
                           } catch (IOException e) {
                               logger.info("Failed to write response for displayable DTO {}", id, e);
                           } finally {
                               asyncContext.complete();
                           }
                       }, executor);
            return null;
        });
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message) {
        response.setStatus(status);
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        try {
            UIJson.WRITER.writeValue(response.getWriter(), Map.of("error", message));
        } catch (IOException e) {
            // best-effort
        }
    }
}
