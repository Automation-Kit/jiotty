package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static net.yudichev.jiotty.user.ui.RequestContextFilter.requestContext;

/// Single servlet handling every `/ui/api/*` request. Body is one line: delegate to [UIServerRuntime#dispatchApiPath]; on miss, write 404. All built-in and
/// user-registered endpoints live behind [ApiPathHandler]s; this servlet does not switch on method or path.
final class ApiServlet extends HttpServlet {

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) {
        if (!requestContext(request).uiServerRuntime().dispatchApiPath(request, response)) {
            writeUnknownPath(response);
        }
    }

    static void writeUnknownPath(HttpServletResponse response) {
        response.setStatus(404);
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        try {
            response.getWriter().print("{\"error\":\"Unknown path\"}");
        } catch (IOException e) {
            // Best-effort: response is already failing.
        }
    }
}
