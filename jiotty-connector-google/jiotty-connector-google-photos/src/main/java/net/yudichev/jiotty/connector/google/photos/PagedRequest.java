package net.yudichev.jiotty.connector.google.photos;

import com.google.api.gax.paging.Page;
import com.google.api.gax.paging.PagedListResponse;
import org.apache.logging.log4j.Logger;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.emptyToNull;

// Although google photos java API natively supports pages, in my case operations that return thousands of items fail with 502 Bad Gateway
// but work fine if pagination is implemented manually, which is what this class does
final class PagedRequest<T> {
    @SuppressWarnings("NonConstantLogger") // as designed
    private final Logger logger;
    private final IntConsumer itemCountProgressCallback;
    private final Function<Optional<String>, PagedListResponse<T>> requestInvoker;

    PagedRequest(Logger logger, IntConsumer itemCountProgressCallback, Function<Optional<String>, PagedListResponse<T>> requestInvoker) {
        this.logger = checkNotNull(logger);
        this.itemCountProgressCallback = checkNotNull(itemCountProgressCallback);
        this.requestInvoker = checkNotNull(requestInvoker);
    }

    Stream<T> getAll() {
        String pageToken = null;
        Stream.Builder<T> streamBuilder = Stream.builder();
        int totalCount = 0;
        do {
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("Thread has been interrupted");
            }
            logger.debug("Requesting list, page token [{}]", pageToken);
            PagedListResponse<T> listResponse = requestInvoker.apply(Optional.ofNullable(pageToken));
            Page<T> page = listResponse.getPage();
            logger.debug("Received response to list request, page token [{}], next page token [{}]", pageToken, page.getNextPageToken());
            for (T value : page.getValues()) {
                streamBuilder.add(value);
                totalCount++;
            }
            itemCountProgressCallback.accept(totalCount);
            pageToken = emptyToNull(listResponse.getNextPageToken());
        } while (pageToken != null);
        return streamBuilder.build();
    }
}
