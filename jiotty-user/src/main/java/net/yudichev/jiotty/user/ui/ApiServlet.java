package net.yudichev.jiotty.user.ui;

import com.google.common.annotations.VisibleForTesting;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import static com.google.common.net.HttpHeaders.RETRY_AFTER;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.user.ui.RequestContextFilter.requestContext;

/// Single servlet handling every `/ui/api/*` request. Body delegates to [UIServerRuntime#dispatchApiPath] and renders its outcome: a miss writes 404, a
/// stopped/mid-lifecycle runtime writes a retryable 503. All built-in and user-registered endpoints live behind [ApiPathHandler]s; this servlet does not switch
/// on method or path.
final class ApiServlet extends BaseHttpServlet {
    @VisibleForTesting
    static final String UNKNOWN_PATH_ERROR = "Unknown path";
    @VisibleForTesting
    static final String UNAVAILABLE_ERROR = "Temporarily unavailable";

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) {
        switch (requestContext(request).uiServerRuntime().dispatchApiPath(request, response)) {
            case HANDLED -> {}
            case NOT_FOUND -> writeUnknownPath(response);
            case UNAVAILABLE -> writeUnavailable(response);
        }
    }

    static void writeUnknownPath(HttpServletResponse response) {
        ErrorResponse.write(response, NOT_FOUND_404, UNKNOWN_PATH_ERROR);
    }

    static void writeUnavailable(HttpServletResponse response) {
        response.setHeader(RETRY_AFTER, "1");
        ErrorResponse.write(response, SERVICE_UNAVAILABLE_503, UNAVAILABLE_ERROR);
    }
}
