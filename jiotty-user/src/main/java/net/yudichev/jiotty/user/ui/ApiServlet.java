package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static net.yudichev.jiotty.user.ui.RequestContextFilter.requestContext;

/// Single servlet handling every `/ui/api/*` request. Body delegates to [UIServerRuntime#dispatchApiPath] and renders its outcome: a miss writes 404, a
/// stopped/mid-lifecycle runtime writes a retryable 503. All built-in and user-registered endpoints live behind [ApiPathHandler]s; this servlet does not switch
/// on method or path.
final class ApiServlet extends HttpServlet {

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) {
        switch (requestContext(request).uiServerRuntime().dispatchApiPath(request, response)) {
            case HANDLED -> {}
            case NOT_FOUND -> writeUnknownPath(response);
            case UNAVAILABLE -> writeUnavailable(response);
        }
    }

    static void writeUnknownPath(HttpServletResponse response) {
        writeError(response, 404, "Unknown path");
    }

    static void writeUnavailable(HttpServletResponse response) {
        response.setHeader("Retry-After", "1");
        writeError(response, 503, "Temporarily unavailable");
    }

    private static void writeError(HttpServletResponse response, int status, String message) {
        response.setStatus(status);
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        try {
            response.getWriter().print("{\"error\":\"" + message + "\"}");
        } catch (IOException e) {
            // Best-effort: response is already failing.
        }
    }
}
