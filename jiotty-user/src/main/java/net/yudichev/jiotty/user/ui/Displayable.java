package net.yudichev.jiotty.user.ui;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.io.Answer;

import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;

public interface Displayable {
    /// The answer to a request for a download the displayable does not have.
    Answer NO_DOWNLOAD = Answer.of(NOT_FOUND_404);

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

    /// Returns the answer to a request for one of this displayable's downloads.
    ///
    /// @param downloadId the download's id, as this displayable's own data names it
    /// @return the answer, failed when the download cannot be produced
    /// @implSpec The answer's body must hold immutable data, because it is encoded on the container thread that writes it.
    default CompletableFuture<Answer> handleDownload(String downloadId) {
        return completedFuture(NO_DOWNLOAD);
    }
}
