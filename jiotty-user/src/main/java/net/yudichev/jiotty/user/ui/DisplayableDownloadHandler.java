package net.yudichev.jiotty.user.ui;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;

/// Handles `GET /ui/api/displayables/download?displayableId=...&downloadId=...` — serves a downloadable artefact emitted by a displayable.
public final class DisplayableDownloadHandler extends BaseLifecycleComponent implements ApiPathHandler {
    static final String PATH = "/displayables/download";
    private static final Logger logger = LogManager.getLogger(DisplayableDownloadHandler.class);

    private final DisplayableRegistry registry;
    private final Provider<SchedulingExecutor> executorProvider;

    private SchedulingExecutor executor;

    @Inject
    public DisplayableDownloadHandler(DisplayableRegistry registry, @UIExecutor Provider<SchedulingExecutor> executorProvider) {
        this.registry = checkNotNull(registry, "registry");
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
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
            AsyncContext asyncContext = request.startAsync();
            asyncContext.start(() -> whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
                var displayableId = request.getParameter("displayableId");
                var displayable = registry.find(displayableId).orElse(null);
                if (displayable == null) {
                    response.setStatus(404);
                    response.getWriter().print("No displayable found with id='" + displayableId + "'");
                    asyncContext.complete();
                } else {
                    String downloadId = request.getParameter("downloadId");
                    if (downloadId == null) {
                        response.setStatus(404);
                        response.getWriter().print("Missing 'downloadId' parameter");
                        asyncContext.complete();
                    } else {
                        displayable.handleDownload(downloadId, response)
                                   .whenCompleteAsync((_, throwable) -> {
                                       try {
                                           if (throwable != null) {
                                               logger.debug("Displayable {} download {} failed", displayableId, downloadId, throwable);
                                               response.setStatus(400);
                                               response.getWriter().write(humanReadableMessage(throwable));
                                           }
                                       } catch (IOException e) {
                                           logger.warn("Error while sending error response for displayable {}", displayableId, e);
                                       } finally {
                                           asyncContext.complete();
                                       }
                                   }, executor);
                    }
                }
            })));
        }));
    }
}
