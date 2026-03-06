package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Appender;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.util.concurrent.CompletableFuture.completedFuture;

public final class StatusHistoryDisplayable<K, T> implements Displayable {
    private final Map<K, DeviceStats> statsByKey = new HashMap<>();
    private final String title;
    private final Function<K, String> keyToKeyTitle;
    private final Function<DeviceStatus<T>, String> statusToEventTime;
    private final BiConsumer<DeviceStatus<T>, Appender> statusRenderer;
    private final BiConsumer<String, HttpServletResponse> downloadHandler;
    private final int windowSize;
    private final TextFormat textFormat;
    private final Listeners<Void> listeners = new Listeners<>();

    public StatusHistoryDisplayable(String title, int windowSize, Function<K, String> keyToKeyTitle) {
        this(title,
             windowSize,
             keyToKeyTitle,
             status -> status.lastChanged().atZone(ZoneId.systemDefault()).format(RFC_1123_DATE_TIME),
             (status, appender) -> appender.append(status.status()),
             (_, _) -> {},
             TextFormat.PLAIN);
    }

    public StatusHistoryDisplayable(String title,
                                    int windowSize,
                                    Function<K, String> keyToKeyTitle,
                                    Function<DeviceStatus<T>, String> statusToEventTime,
                                    BiConsumer<DeviceStatus<T>, Appender> statusRenderer,
                                    BiConsumer<String, HttpServletResponse> downloadHandler,
                                    TextFormat textFormat) {
        this.title = checkNotNull(title);
        this.keyToKeyTitle = checkNotNull(keyToKeyTitle);
        this.statusToEventTime = checkNotNull(statusToEventTime);
        this.statusRenderer = checkNotNull(statusRenderer);
        this.downloadHandler = checkNotNull(downloadHandler);
        checkArgument(windowSize > 0);
        this.windowSize = windowSize;
        this.textFormat = checkNotNull(textFormat);
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
    public CompletableFuture<Void> handleDownload(String downloadId, HttpServletResponse resp) {
        downloadHandler.accept(downloadId, resp);
        return completedFuture(null);
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
    public CompletableFuture<DisplayableDtos.DisplayableDto> toDto() {
        var groups = new LinkedHashMap<String, List<DisplayableDtos.HistoryEntry>>();
        synchronized (statsByKey) {
            statsByKey.forEach((key, stats) -> {
                String what = key == null ? "" : keyToKeyTitle.apply(key);
                var entries = new ArrayList<DisplayableDtos.HistoryEntry>();
                var sb = new StringBuilder(64);
                var appender = Appender.wrap(sb);
                // newest first
                stats.statusHistory().descendingIterator().forEachRemaining(status -> {
                    String time = statusToEventTime.apply(status);
                    statusRenderer.accept(status, appender);
                    entries.add(new DisplayableDtos.HistoryEntry(time, sb.toString(), textFormat));
                    sb.setLength(0);
                });
                groups.put(what, entries);
            });
        }
        return completedFuture(new DisplayableDtos.History(groups));
    }

    @Value.Immutable
    @PublicImmutablesStyle
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
