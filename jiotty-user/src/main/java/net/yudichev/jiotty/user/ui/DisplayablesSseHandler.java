package net.yudichev.jiotty.user.ui;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;

/// Handles `GET /ui/api/displayables/stream` — opens a server-sent-events stream and threads the per-user request-context invalidation through to it.
public final class DisplayablesSseHandler extends BaseSseApiPathHandler {
    static final String PATH = "/displayables/stream";

    private final SseService sseService;

    @Inject
    public DisplayablesSseHandler(SseService sseService) {
        super(PATH);
        this.sseService = checkNotNull(sseService, "sseService");
    }

    @Override
    protected Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) throws IOException {
        return sseService.startSse(request, response, onStreamClosed);
    }
}
