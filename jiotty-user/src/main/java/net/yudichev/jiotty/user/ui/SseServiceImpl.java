package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.inject.BindingAnnotation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.throttling.ThrottlingConsumer;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionDto;
import net.yudichev.jiotty.user.ui.sse.SseChannel;
import net.yudichev.jiotty.user.ui.sse.SseChannel.SseSink;
import net.yudichev.jiotty.user.ui.sse.SseChannel.SseStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.ERROR;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;
import static net.yudichev.jiotty.user.ui.UIServerModule.Dependency;
import static net.yudichev.jiotty.user.ui.UIServerModule.SubjectId;

/// Streams one user's options and displayables to their open UI clients over the shared [SseChannel] transport.
public final class SseServiceImpl extends BaseLifecycleComponent implements SseService {
    private static final Logger logger = LogManager.getLogger(SseServiceImpl.class);
    private static final Pattern TAB_NAME_TO_ID_CONVERSION_PATTERN = Pattern.compile("[^A-Za-z0-9_-]");

    private final Provider<SchedulingExecutor> executorProvider;
    private final OptionRegistry optionRegistry;
    private final DisplayableRegistry displayableRegistry;
    private final SseChannel.Factory channelFactory;
    private final AdminAlertService alertService;
    private final Duration optionsThrottlingPeriod;
    private final String userId;
    private final MeterRegistry meterRegistry;
    private final Timer headersToSnapshotStartTimer;
    private final Timer displayablesSnapshotTimer;
    private final Timer optionsSnapshotTimer;

    private SchedulingExecutor executor;
    private SseChannel channel;
    private ThrottlingConsumer<Object> optionSnapshotThrottle;
    private Closeable optionRegistrySubscription;
    private Closeable displayableUpdateSubscription;
    private Closeable displayableRegistrationSubscription;

    @Inject
    public SseServiceImpl(@UIExecutor Provider<SchedulingExecutor> executorProvider,
                          OptionRegistry optionRegistry,
                          DisplayableRegistry displayableRegistry,
                          SseChannel.Factory channelFactory,
                          @Dependency AdminAlertService alertService,
                          @OptionsThrottlingPeriod Duration optionsThrottlingPeriod,
                          @SubjectId String userId,
                          MeterRegistry meterRegistry) {
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.optionRegistry = checkNotNull(optionRegistry, "optionRegistry");
        this.displayableRegistry = checkNotNull(displayableRegistry, "displayableRegistry");
        this.channelFactory = checkNotNull(channelFactory, "channelFactory");
        this.alertService = checkNotNull(alertService, "alertService");
        this.optionsThrottlingPeriod = checkNotNull(optionsThrottlingPeriod, "optionsThrottlingPeriod");
        this.userId = checkNotNull(userId, "userId");
        this.meterRegistry = checkNotNull(meterRegistry, "meterRegistry");
        headersToSnapshotStartTimer = meterRegistry.timer("sse_headers_to_snapshot_start_seconds");
        displayablesSnapshotTimer = meterRegistry.timer("sse_displayables_snapshot_seconds");
        optionsSnapshotTimer = meterRegistry.timer("sse_options_snapshot_seconds");
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        channel = channelFactory.create("ui", executor, UIJson.WRITER, SseChannel.UNBOUNDED);
        channel.start();
        optionSnapshotThrottle = new ThrottlingConsumer<>(executor,
                                                          optionsThrottlingPeriod,
                                                          _ -> ifStartedAndNotLifecycling(() -> sendOptionSnapshotTo(channel::broadcast)));
        optionRegistrySubscription = optionRegistry.subscribeToSnapshotChanges(() -> optionSnapshotThrottle.accept(null));
        displayableUpdateSubscription = displayableRegistry.subscribeToUpdates(displayable -> sendDisplayableUpdate(displayable, channel::broadcast));
        displayableRegistrationSubscription =
                displayableRegistry.subscribeToRegistrations(displayable -> sendDisplayableUpdate(displayable, channel::broadcast));
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger,
                             displayableRegistrationSubscription,
                             displayableUpdateSubscription,
                             optionRegistrySubscription,
                             optionSnapshotThrottle,
                             channel);
    }

    @Override
    public Closeable startSse(HttpServletRequest request, HttpServletResponse response, Runnable onStreamClosed) throws IOException {
        AsyncContext asyncContext = request.startAsync();
        Timer.Sample headersToSnapshotStartSample = Timer.start(meterRegistry);
        Optional<SseStream> stream = channel.open(asyncContext, response, onStreamClosed, sink -> sendInitialImageTo(sink, headersToSnapshotStartSample));
        if (stream.isEmpty()) {
            // The channel is unbounded, so the only refusal is teardown racing this request. Complete the exchange and report the stream closed, so the
            // request does not hang on an async context nobody owns and the caller releases whatever it attached to the stream.
            logger.debug("[{}] SSE stream refused: the channel is closed", userId);
            asyncContext.complete();
            onStreamClosed.run();
            return Closeable.noop();
        }
        return stream.get();
    }

    private void sendInitialImageTo(SseSink sink, Timer.Sample headersToSnapshotStartSample) {
        ifStartedAndNotLifecycling(() -> {
            headersToSnapshotStartSample.stop(headersToSnapshotStartTimer);
            Timer.Sample displayablesSample = Timer.start(meterRegistry);
            Timer.Sample optionsSample = Timer.start(meterRegistry);
            sendDisplayablesSnapshotTo(sink).whenComplete((_, throwable) -> {
                if (throwable == null) {
                    displayablesSample.stop(displayablesSnapshotTimer);
                } else {
                    logger.debug("Displayables snapshot failed before completing the timer", throwable);
                }
            });
            sendOptionSnapshotTo(sink).whenComplete((_, throwable) -> {
                if (throwable == null) {
                    optionsSample.stop(optionsSnapshotTimer);
                } else {
                    logger.debug("Options snapshot failed before completing the timer", throwable);
                }
            });
        });
    }

    /// Runs `action` unless this service has stopped, deciding under the lifecycle lock. Teardown runs on a thread of its own and stops the registries
    /// `action` reads immediately after this service, so holding the lock across the read is what makes that verdict hold for the whole of it.
    private void ifStartedAndNotLifecycling(Runnable action) {
        whenNotLifecycling(() -> {
            if (isStartedOpaque()) {
                action.run();
            }
        });
    }

    private CompletableFuture<?> sendOptionSnapshotTo(SseSink target) {
        return ImmutableList.copyOf(optionRegistry.all()).stream()
                            .map(Option::toDto)
                            .collect(toFutureOfList())
                            .whenCompleteAsync((allOptionDtos, throwable) -> {
                                if (throwable == null) {
                                    var optionsByTabName = LinkedListMultimap.<String, OptionDto>create(allOptionDtos.size());
                                    for (OptionDto optionDto : allOptionDtos) {
                                        optionsByTabName.put(optionDto.tabName(), optionDto);
                                    }
                                    var tabs = new ArrayList<OptionsTab>(optionsByTabName.keySet().size());
                                    optionsByTabName.asMap()
                                                    .forEach((tabName, tabDtos) -> tabs.add(new OptionsTab(toDomId(tabName), tabName, tabDtos)));
                                    target.send("options-update", new OptionsUpdateFrame(tabs));
                                } else {
                                    // A defect in this server, and it leaves the user's options tab stale, so it goes to an operator.
                                    alertService.raise(ERROR, "Failed to generate options DTOs", logger, throwable);
                                }
                            }, executor);
    }

    private CompletableFuture<Void> sendDisplayablesSnapshotTo(SseSink target) {
        Collection<Displayable> allDisplayables = displayableRegistry.all();
        var futures = new ArrayList<CompletableFuture<?>>(allDisplayables.size());
        for (Displayable displayable : allDisplayables) {
            futures.add(sendDisplayableUpdate(displayable, target));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    private CompletableFuture<?> sendDisplayableUpdate(Displayable displayable, SseSink target) {
        return displayable.toDto().whenCompleteAsync((displayableDto, throwable) -> {
            if (throwable != null) {
                alertService.raise(ERROR, "Displayable failed to generate its DTO", logger, displayable.getId(), throwable);
                return;
            }
            target.send("displayable-update", new DisplayableUpdateFrame(displayable.getId(), displayableDto));
        }, executor);
    }

    private static String toDomId(String raw) {
        return TAB_NAME_TO_ID_CONVERSION_PATTERN.matcher(raw).replaceAll("-");
    }

    private record OptionsUpdateFrame(List<OptionsTab> tabs) {}

    private record OptionsTab(String id, String name, Collection<OptionDto> options) {}

    private record DisplayableUpdateFrame(String id, DisplayableDto dto) {}

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface OptionsThrottlingPeriod {
    }
}
