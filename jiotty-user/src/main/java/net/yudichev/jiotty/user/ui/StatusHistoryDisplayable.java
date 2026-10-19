package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import net.yudichev.jiotty.user.ui.io.Answer;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

public final class StatusHistoryDisplayable<K, T> implements Displayable {
    private final Map<K, DeviceStats> statsByKey = new HashMap<>();
    private final String title;
    private final Function<K, String> keyToKeyTitle;
    private final Function<DeviceStatus<T>, Instant> statusToEventTime;
    private final @Nullable BiConsumer<DeviceStatus<T>, Appendable> statusRenderer;
    private final Function<String, CompletableFuture<Answer>> downloadHandler;
    private final int windowSize;
    private final HistoryDisplayableDto.Format entryFormat;
    private final Listeners<Void> listeners = new Listeners<>();

    public StatusHistoryDisplayable(String title, int windowSize, Function<K, String> keyToKeyTitle) {
        this(title,
             windowSize,
             keyToKeyTitle,
             DeviceStatus::lastChanged,
             (status, appendable) -> Append.to(appendable, status.status()),
             _ -> completedFuture(NO_DOWNLOAD),
             HistoryDisplayableDto.Format.PLAIN_TEXT);
    }

    public StatusHistoryDisplayable(String title,
                                    int windowSize,
                                    Function<K, String> keyToKeyTitle,
                                    Function<DeviceStatus<T>, Instant> statusToEventTime,
                                    @Nullable BiConsumer<DeviceStatus<T>, Appendable> statusRenderer,
                                    Function<String, CompletableFuture<Answer>> downloadHandler,
                                    HistoryDisplayableDto.Format entryFormat) {
        this.title = checkNotNull(title);
        this.keyToKeyTitle = checkNotNull(keyToKeyTitle);
        this.statusToEventTime = checkNotNull(statusToEventTime);
        checkArgument((entryFormat == HistoryDisplayableDto.Format.OBJECT) == (statusRenderer == null),
                      "entryFormat %s is not compatible with statusRenderer being %s", entryFormat, statusRenderer == null ? "null" : "not null");
        this.statusRenderer = statusRenderer;
        this.downloadHandler = checkNotNull(downloadHandler);
        checkArgument(windowSize > 0);
        this.windowSize = windowSize;
        this.entryFormat = checkNotNull(entryFormat);
    }

    @Override
    public Closeable subscribeForUpdates(Runnable updatesAvailable) {
        updatesAvailable.run();
        return listeners.addListener(_ -> updatesAvailable.run());
    }

    public void addEvent(K key, T value, Instant lastChanged) {
        synchronized (statsByKey) {
            statsByKey.computeIfAbsent(key, ignored -> new DeviceStats()).add(DeviceStatus.<T>builder().setLastChanged(lastChanged).setStatus(value).build());
        }
        listeners.notify(null);
    }

    @Override
    public String getId() {
        return title;
    }

    @Override
    public CompletableFuture<Answer> handleDownload(String downloadId) {
        return downloadHandler.apply(downloadId);
    }

    @Override
    public boolean supportsData() {
        return true;
    }

    @Override
    public boolean visible() {
        return true;
    }

    @Override
    public CompletableFuture<DisplayableDto> toDto() {
        ImmutableMap.Builder<String, ImmutableList<HistoryDisplayableDto.Entry>> entriesByKeyTitleBuilder;
        synchronized (statsByKey) {
            entriesByKeyTitleBuilder = ImmutableMap.builderWithExpectedSize(statsByKey.size());
            statsByKey.forEach((key, stats) -> {
                String keyTitle = key == null ? "" : keyToKeyTitle.apply(key);
                var entries = ImmutableList.<HistoryDisplayableDto.Entry>builderWithExpectedSize(stats.statusHistory().size());
                switch (entryFormat) {
                    case PLAIN_TEXT, HTML -> {
                        var sb = new StringBuilder(64);
                        // newest first
                        stats.statusHistory().descendingIterator().forEachRemaining(status -> {
                            Instant time = statusToEventTime.apply(status);
                            assert statusRenderer != null;
                            statusRenderer.accept(status, sb);
                            entries.add(new HistoryDisplayableDto.Entry(time, entryFormat, sb.toString()));
                            sb.setLength(0);
                        });

                    }
                    case OBJECT -> // newest first
                            stats.statusHistory().descendingIterator().forEachRemaining(status -> {
                                Instant time = statusToEventTime.apply(status);
                                entries.add(new HistoryDisplayableDto.Entry(time, entryFormat, status.status()));
                            });
                }
                entriesByKeyTitleBuilder.put(keyTitle, entries.build());
            });
        }
        return completedFuture(new HistoryDisplayableDto(entriesByKeyTitleBuilder.buildKeepingLast()));
    }

    @Value.Immutable
    @PublicImmutablesStyle
    @JsonSerialize
    interface BaseDeviceStatus<T> {
        @Value.Parameter
        T status();

        @Value.Parameter
        Instant lastChanged();
    }

    private final class DeviceStats {

        private final Deque<DeviceStatus<T>> history = new LinkedList<>();

        public Deque<DeviceStatus<T>> statusHistory() {
            return history;
        }

        public void add(DeviceStatus<T> status) {
            history.add(status);
            if (history.size() > windowSize) {
                history.poll();
            }
        }
    }
}
