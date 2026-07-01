package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.inject.BindingAnnotation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.throttling.ThrottlingConsumer;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionDto;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.StringBuilderFormattable;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.appendHumanReadableMessage;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;

public final class SseServiceImpl extends BaseLifecycleComponent implements SseService {
    private static final Logger logger = LogManager.getLogger(SseServiceImpl.class);
    private static final Pattern TAB_NAME_TO_ID_CONVERSION_PATTERN = Pattern.compile("[^A-Za-z0-9_-]");
    private static final Duration HEARTBEAT_PERIOD = Duration.ofSeconds(15);

    private final Set<SseClient> sseClients = new HashSet<>();
    private final AtomicInteger sseClientIdGenerator = new AtomicInteger();

    private final Provider<SchedulingExecutor> executorProvider;
    private final OptionRegistry optionRegistry;
    private final DisplayableRegistry displayableRegistry;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Duration optionsThrottlingPeriod;
    private final MeterRegistry meterRegistry;
    private final Timer headersToSnapshotStartTimer;
    private final Timer displayablesSnapshotTimer;
    private final Timer optionsSnapshotTimer;

    private SchedulingExecutor executor;
    private ThrottlingConsumer<Object> optionSnapshotThrottle;
    private Closeable sseHeartbeat = Closeable.noop();
    private Closeable optionRegistrySubscription;
    private Closeable displayableUpdateSubscription;
    private Closeable displayableRegistrationSubscription;

    @Inject
    public SseServiceImpl(@UIExecutor Provider<SchedulingExecutor> executorProvider,
                          OptionRegistry optionRegistry,
                          DisplayableRegistry displayableRegistry,
                          CurrentDateTimeProvider currentDateTimeProvider,
                          @OptionsThrottlingPeriod Duration optionsThrottlingPeriod,
                          MeterRegistry meterRegistry) {
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.optionRegistry = checkNotNull(optionRegistry, "optionRegistry");
        this.displayableRegistry = checkNotNull(displayableRegistry, "displayableRegistry");
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider, "currentDateTimeProvider");
        this.optionsThrottlingPeriod = checkNotNull(optionsThrottlingPeriod, "optionsThrottlingPeriod");
        this.meterRegistry = checkNotNull(meterRegistry, "meterRegistry");
        headersToSnapshotStartTimer = meterRegistry.timer("sse_headers_to_snapshot_start_seconds");
        displayablesSnapshotTimer = meterRegistry.timer("sse_displayables_snapshot_seconds");
        optionsSnapshotTimer = meterRegistry.timer("sse_options_snapshot_seconds");
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        optionSnapshotThrottle = new ThrottlingConsumer<>(executor, optionsThrottlingPeriod, _ -> broadcastOptionSnapshot());
        sseHeartbeat = executor.scheduleAtFixedRate(HEARTBEAT_PERIOD, this::sendSseHeartbeat);
        optionRegistrySubscription = optionRegistry.subscribeToSnapshotChanges(() -> optionSnapshotThrottle.accept(null));
        displayableUpdateSubscription = displayableRegistry.subscribeToUpdates(displayable -> sendDisplayableUpdate(displayable, sseClients));
        displayableRegistrationSubscription = displayableRegistry.subscribeToRegistrations(displayable -> sendDisplayableUpdate(displayable, sseClients));
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger,
                             displayableRegistrationSubscription,
                             displayableUpdateSubscription,
                             optionRegistrySubscription,
                             sseHeartbeat,
                             optionSnapshotThrottle);
        executor.execute(() -> {
            for (var client : List.copyOf(sseClients)) {
                closeAndRemoveClient(client);
            }
        });
    }

    @Override
    public Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) throws IOException {
        int clientIdSeqNum = sseClientIdGenerator.incrementAndGet();
        String clientId = clientIdSeqNum + "/" + request.getRemoteHost() + ":" + request.getRemotePort();
        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);
        response.setCharacterEncoding("utf-8");
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("X-Client-Id-Seq-Num", String.valueOf(clientIdSeqNum));
        response.flushBuffer();
        Timer.Sample headersToSnapshotStartSample = Timer.start(meterRegistry);

        var client = new SseClient(asyncContext, clientId, clientIdSeqNum, idempotent(onStreamClosed::run));
        logger.debug("[SSE {}] created", clientId);
        executor.execute(() -> {
            sseClients.add(client);
            client.init();
            logger.debug("[SSE {}] delivering initial image", clientId);
            headersToSnapshotStartSample.stop(headersToSnapshotStartTimer);
            Timer.Sample displayablesSample = Timer.start(meterRegistry);
            Timer.Sample optionsSample = Timer.start(meterRegistry);
            var targetClients = List.of(client);
            sendDisplayablesSnapshotTo(targetClients).whenComplete((_, throwable) -> {
                if (throwable == null) {
                    displayablesSample.stop(displayablesSnapshotTimer);
                } else {
                    logger.debug("[SSE {}] displayables snapshot failed before completing the timer", clientId, throwable);
                }
            });
            sendOptionSnapshotTo(targetClients).whenComplete((_, throwable) -> {
                if (throwable == null) {
                    optionsSample.stop(optionsSnapshotTimer);
                } else {
                    logger.debug("[SSE {}] options snapshot failed before completing the timer", clientId, throwable);
                }
            });
            try {
                asyncContext.addListener(new AsyncListener() {
                    @Override
                    public void onComplete(AsyncEvent event) {
                        logger.debug("[SSE {}] onComplete", clientId);
                        removeClient();
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) {
                        logger.debug("[SSE {}] onTimeout", clientId);
                        removeClient();
                    }

                    @Override
                    public void onError(AsyncEvent event) {
                        logger.debug("[SSE {}] onError: {}",
                                     clientId, (StringBuilderFormattable) buffer -> appendHumanReadableMessage(event.getThrowable(), buffer));
                        removeClient();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                        logger.debug("[SSE {}] onStartAsync", clientId);
                    }

                    private void removeClient() {
                        if (isStarted()) {
                            executor.execute(() -> closeAndRemoveClient(client));
                        }
                    }
                });
            } catch (RuntimeException e) {
                logger.debug("[SSE {}] asyncContext.addListener failed", clientId, e);
                closeAndRemoveClient(client);
            }
        });
        // Guard with isStarted() exactly as removeClient() does: once this component is stopped the executor is terminated and its clients were drained during
        //  stop, so the close is a no-op. Without the guard, a close arriving after stop would schedule onto the terminated executor and throw
        //  RejectedExecutionException into the caller.
        return idempotent(() -> {
            if (isStarted()) {
                executor.execute(() -> closeAndRemoveClient(client));
            }
        });
    }

    private void closeAndRemoveClient(SseClient client) {
        closeSafelyIfNotNull(logger, client);
        sseClients.remove(client);
    }

    private void broadcastOptionSnapshot() {
        sendOptionSnapshotTo(sseClients);
    }

    private CompletableFuture<?> sendOptionSnapshotTo(Iterable<SseClient> clients) {
        return ImmutableList.copyOf(optionRegistry.all()).stream()
                            .map(Option::toDto)
                            .collect(toFutureOfList())
                            .whenCompleteAsync((allOptionDtos, throwable) -> {
                                if (throwable == null) {
                                    var optionsByTabName = LinkedListMultimap.<String, OptionDto>create(allOptionDtos.size());
                                    for (OptionDto optionDto : allOptionDtos) {
                                        optionsByTabName.put(optionDto.tabName(), optionDto);
                                    }
                                    var tabs = new ArrayList<Map<String, Object>>(optionsByTabName.keySet().size());
                                    optionsByTabName.asMap().forEach((tabName, tabDtos) ->
                                                                             tabs.add(Map.of("id", toDomId(tabName), "name", tabName, "options", tabDtos)));
                                    broadcastSse("options-update", Map.of("tabs", tabs), clients);
                                } else {
                                    // this is a bug, so fine to spam
                                    logger.warn("Failed to generate options DTOs", throwable);
                                }
                            }, executor);
    }

    private CompletableFuture<Void> sendDisplayablesSnapshotTo(List<SseClient> targetClients) {
        Collection<Displayable> allDisplayables = displayableRegistry.all();
        var futures = new ArrayList<CompletableFuture<?>>(allDisplayables.size());
        for (Displayable displayable : allDisplayables) {
            futures.add(sendDisplayableUpdate(displayable, targetClients));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    private CompletableFuture<?> sendDisplayableUpdate(Displayable displayable, Iterable<SseClient> clients) {
        return displayable.toDto().whenCompleteAsync((displayableDto, throwable) -> {
            if (throwable != null) {
                logger.warn("Displayable {} failed to generate DTO", displayable.getId(), throwable);
                return;
            }
            broadcastSse("displayable-update", Map.of("id", displayable.getId(), "dto", displayableDto), clients);
        }, executor);
    }

    private static String toDomId(String raw) {
        return TAB_NAME_TO_ID_CONVERSION_PATTERN.matcher(raw).replaceAll("-");
    }

    private static void broadcastSse(String eventName, Object data, Iterable<SseClient> clients) {
        for (SseClient client : clients) {
            client.sendEvent(eventName, data);
        }
    }

    private void sendSseHeartbeat() {
        var ping = Map.of("serverTime", currentDateTimeProvider.currentInstant());
        for (SseClient client : sseClients) {
            client.sendEvent("ping", ping);
        }
    }

    @SuppressWarnings("HardcodedLineSeparator")
    private final class SseClient implements Closeable {
        private final AsyncContext asyncContext;
        private final ServletOutputStream out;
        private final int clientIdSeqNum;
        private final String clientId;
        private final Closeable onStreamClosed;
        private boolean closed;

        SseClient(AsyncContext asyncContext, String clientId, int clientIdSeqNum, Closeable onStreamClosed) throws IOException {
            this.asyncContext = checkNotNull(asyncContext);
            this.clientId = checkNotNull(clientId);
            out = asyncContext.getResponse().getOutputStream();
            this.clientIdSeqNum = clientIdSeqNum;
            this.onStreamClosed = checkNotNull(onStreamClosed);
        }

        private void init() {
            try {
                logger.debug("[SSE {}] init", clientId);
                out.print("retry: 3000\n\n");
                sendEvent("hello", Map.of("clientIdSeqNum", clientIdSeqNum,
                                          "serverTime", currentDateTimeProvider.currentInstant()));
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        private void sendEvent(String eventName, Object data) {
            try {
                logger.debug("[SSE {}] send event {}, {}", clientId, eventName, logger.isTraceEnabled() ? UIJson.WRITER.writeValueAsString(data) : data);
                out.print("event: ");
                out.print(eventName);
                out.print('\n');
                out.print("data: ");
                UIJson.WRITER.writeValue(out, data);
                out.print("\n\n");
                out.flush();
            } catch (IOException e) {
                logger.debug("[SSE {}] failed to send event {}", clientId, eventName, e);
                close();
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                logger.debug("[SSE {}] closed", clientId);
                asyncContext.complete();
            } catch (RuntimeException ignored) {
            }
            onStreamClosed.close();
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface OptionsThrottlingPeriod {
    }
}
