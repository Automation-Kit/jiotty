package net.yudichev.jiotty.user.ui;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import java.util.Locale;

/// JSON error responder. Used in two spots: as the trailing fallback in [UIHttpServerImpl]'s [Handler.Sequence] for paths matching no mount (where the response
/// status has not been set yet and defaults to 404), and as the per-context error handler in [StaticResourceServletMount] for any error the static SPA mount
/// emits (where the upstream servlet has already set the status — 404 for a missing file, 405 for an unsupported method, 416 for a bad range, 5xx for an
/// internal failure, etc.). Writes `{"error":"<reason>"}` matching the in-mount envelope used by [ApiServlet#writeUnknownPath]; the reason is the sentence-case
/// form of the standard HTTP status phrase for the current status code.
final class JsonErrorHandler extends Handler.Abstract {
    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        int status = response.getStatus();
        if (status <= 0) {
            status = HttpStatus.NOT_FOUND_404;
            response.setStatus(status);
        }
        response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.APPLICATION_JSON_UTF_8.asString());
        Content.Sink.write(response, true, "{\"error\":\"" + sentenceCaseReason(status) + "\"}", callback);
        return true;
    }

    static String sentenceCaseReason(int status) {
        assert status > 0 : "status must be positive: " + status;
        String reason = HttpStatus.getMessage(status);
        if (reason == null || reason.isEmpty()) {
            return Integer.toString(status);
        }
        return Character.toUpperCase(reason.charAt(0)) + reason.substring(1).toLowerCase(Locale.ROOT);
    }
}
