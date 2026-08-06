package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Closeable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;

public interface Displayable {
    String getId();

    default String getDisplayName() {
        return getId();
    }

    /// @return `true` if this displayable produces data or `false` if it only supports downloads
    default boolean supportsData() {
        return false;
    }

    /// @return `true` if this displayable needs to be displayed as a tab in the UI
    default boolean visible() {
        return false;
    }

    /// Subscribe for any updates on this displayable. Use [#toDto()] to obtain the latest data.
    default Closeable subscribeForUpdates(Runnable updatesAvailable) {
        return () -> {};
    }

    default CompletableFuture<DisplayableDto> toDto() {
        return null;
    }

    default CompletableFuture<Void> handleDownload(String downloadId, HttpServletResponse resp) throws IOException {
        resp.sendError(NOT_FOUND_404, "Downloads are not supported by " + getDisplayName());
        return completedFuture(null);
    }
}
