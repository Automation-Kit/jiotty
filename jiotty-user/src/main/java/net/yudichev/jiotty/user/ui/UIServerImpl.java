package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.inject.BindingAnnotation;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Appender;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.StabilisingConsumer;
import net.yudichev.jiotty.common.lang.throttling.ThrottlingConsumer;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionDto;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.StringBuilderFormattable;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.forCloseables;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.Closeable.noop;
import static net.yudichev.jiotty.common.lang.CompletableFutures.toFutureOfList;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.appendHumanReadableMessage;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

public final class UIServerImpl extends BaseLifecycleComponent implements UIServer, UIServerRuntime {
    private static final Logger logger = LogManager.getLogger(UIServerImpl.class);
    private static final Pattern TAB_NAME_TO_ID_CONVERSION_PATTERN = Pattern.compile("[^A-Za-z0-9_-]");
    private static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory())
            .registerModule(new JavaTimeModule())
            .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    private final Map<String, Displayable> displayablesById = new LinkedHashMap<>();
    private final Map<String, Option<?>> optionsByKey = new LinkedHashMap<>();
    private final OptionPersistence persistence;
    private final List<Closeable> optionsPersistenceRegistrations = new ArrayList<>();
    private final ExecutorFactory executorFactory;
    private final String threadNameSuffix;
    private final Duration optionsStabilisationDelay;
    private final Set<SseClient> sseClients = new HashSet<>();
    private final AtomicInteger sseClientIdGenerator = new AtomicInteger();

    private SchedulingExecutor executor;
    private Closeable sseHeartbeat = Closeable.noop();
    private StabilisingConsumer<Void> optionSnapshotStabiliser;

    @Inject
    public UIServerImpl(OptionPersistence persistence,
                        ExecutorFactory executorFactory,
                        @ThreadSuffix String threadNameSuffix,
                        @OptionsStabilisationDelay Duration optionsStabilisationDelay) {
        this.persistence = checkNotNull(persistence);
        this.executorFactory = checkNotNull(executorFactory);
        this.threadNameSuffix = checkNotNull(threadNameSuffix);
        this.optionsStabilisationDelay = checkNotNull(optionsStabilisationDelay);
    }

    @Override
    public Closeable registerDisplayable(Displayable displayable) {
        return whenStartedAndNotLifecycling(() -> {
            checkArgument(displayablesById.putIfAbsent(displayable.getId(), displayable) == null,
                          "Displayable with id '%s' is already registered", displayable.getId());
            Closeable dataSubscription;
            @Nullable ThrottlingConsumer<Void> throttle;
            if (displayable.supportsData()) {
                throttle = new ThrottlingConsumer<>(executor, Duration.ofSeconds(1), _ -> sendDisplayableUpdate(displayable));
                dataSubscription = displayable.subscribeForUpdates(() -> throttle.accept(null));
            } else {
                dataSubscription = noop();
                throttle = null;
            }
            logger.info("Registered displayable {} with title {}", displayable, displayable.getDisplayName());
            // deliver image to existing SSE clients
            sendDisplayableUpdate(displayable);
            return idempotent(() -> whenStartedAndNotLifecycling(() -> {
                if (displayablesById.remove(displayable.getId(), displayable)) {
                    Closeable.closeSafelyIfNotNull(logger, throttle, dataSubscription);
                    logger.info("Unregistered displayable {} with title {}", displayable, displayable.getDisplayName());
                }
            }));
        });
    }


    @Override
    public Closeable registerOption(Option<?> option) {
        return whenStartedAndNotLifecycling(() -> {
            checkArgument(!optionsByKey.containsKey(option.meta().key()), "Option for key %s already registered: %s", option.meta().key(), option);
            persistence.load(option);
            optionsByKey.put(option.meta().key(), option);
            Closeable persistenceRegistration = option.addChangeListener(theOption -> {
                persistence.save(theOption);
                notifyOptionSnapshotChanged(); // devise some cleverer snapshot/delta protocol, if ever needed
            });
            optionsPersistenceRegistrations.add(persistenceRegistration);
            logger.info("Registered option {}", option.meta().key());
            notifyOptionSnapshotChanged();
            return idempotent(() -> whenNotLifecycling(() -> {
                optionsByKey.remove(option.meta().key());
                Closeable.closeIfNotNull(persistenceRegistration);
                optionsPersistenceRegistrations.remove(persistenceRegistration);
                logger.info("Unregistered option {}", option.meta().key());
                notifyOptionSnapshotChanged();
            }));
        });
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("UI" + (threadNameSuffix.isBlank() ? "" : '-' + threadNameSuffix));
        optionSnapshotStabiliser = new StabilisingConsumer<>(executor, optionsStabilisationDelay, _ -> broadcastOptionSnapshot());
        sseHeartbeat = executor.scheduleAtFixedRate(Duration.ofSeconds(15), this::sendSseHeartbeat);
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, sseHeartbeat);
        closeSafelyIfNotNull(logger, forCloseables(optionsPersistenceRegistrations), executor);
    }

    @Override
    public void handleOptionsPost(HttpServletRequest request, HttpServletResponse response) {
        whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
            AsyncContext asyncContext = request.startAsync();
            asyncContext.start(() -> {
                if (logger.isDebugEnabled()) {
                    logger.debug("Form parameters: {}",
                                 request.getParameterMap().entrySet().stream()
                                        .map(entry -> entry.getKey() + '=' + Arrays.toString(entry.getValue()))
                                        .toList());
                }
                try {
                    whenStartedAndNotLifecycling(() -> {
                        var optionKey = request.getParameter("name");
                        checkArgument(optionKey != null, "Missing name parameter");
                        Option<?> option = optionsByKey.get(optionKey);
                        checkArgument(option != null, "Unknown optionKey: %s, known options are: %s", optionKey, optionsByKey.keySet());
                        option.onFormSubmit(Optional.ofNullable(request.getParameter("value")))
                              .whenCompleteAsync((responseData, throwable) -> {
                                  try {
                                      response.setCharacterEncoding("utf-8");
                                      if (throwable != null) {
                                          writeOptionFormPostFailure(response, throwable);
                                      } else {
                                          response.setContentType("application/json");
                                          MAPPER.writeValue(response.getWriter(), responseData);
                                      }
                                  } catch (IOException e) {
                                      logger.warn("Value rendering failed for option {}", option, e);
                                  } finally {
                                      asyncContext.complete();
                                  }
                              }, executor);
                    });
                } catch (RuntimeException e) {
                    response.setCharacterEncoding("utf-8");
                    try {
                        writeOptionFormPostFailure(response, e);
                    } catch (IOException ex) {
                        logger.warn("Failed writing option submit failure response", e);
                    } finally {
                        asyncContext.complete();
                    }
                }
            });
        }));
    }

    private static void writeOptionFormPostFailure(HttpServletResponse response, Throwable throwable) throws IOException {
        response.setContentType("text/plain");
        logger.info("Option form submission failed", throwable);
        response.setStatus(400);
        response.getWriter().write(humanReadableMessage(throwable));
    }

    private void sendOptionSnapshotTo(Iterable<SseClient> clients) {
        ImmutableList.copyOf(optionsByKey.values()).stream().map(Option::toDto).collect(toFutureOfList()).whenCompleteAsync((allOptionDtos, throwable) -> {
            if (throwable == null) {
                var optionsByTabName = LinkedListMultimap.<String, OptionDto>create(allOptionDtos.size());
                for (OptionDto optionDto : allOptionDtos) {
                    optionsByTabName.put(optionDto.tabName(), optionDto);
                }
                var tabs = new ArrayList<Map<String, Object>>(optionsByTabName.keySet().size());
                optionsByTabName.asMap().forEach((tabName, tabDtos) -> tabs.add(Map.of("id", toDomId(tabName), "name", tabName, "options", tabDtos)));
                broadcastSse("options-update", Map.of("tabs", tabs), clients);
            } else {
                // this is a bug, so fine to spam
                logger.warn("Failed to generate options DTOs", throwable);
            }
        }, executor);
    }

    @Override
    public void writeDisplayablesListJson(HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        Map<String, Displayable> displayablesByIdCopy = whenStartedAndNotLifecycling(() -> new HashMap<>(displayablesById));
        var items = new ArrayList<Map<String, Object>>(displayablesByIdCopy.size());
        displayablesByIdCopy.forEach((id, displayable) -> {
            if (displayable.visible()) {
                items.add(Map.of("id", id, "name", displayable.getDisplayName(), "safeId", toDomId(id)));
            }
        });
        MAPPER.writeValue(response.getWriter(), Map.of("items", items));
    }

    @Override
    public void writeDisplayableItemJson(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        String id = request.getParameter("id");
        if (id == null || id.isBlank()) {
            response.getWriter().write("{\"error\":\"missing id\"}");
            response.setStatus(400);
            return;
        }
        Displayable displayable = whenStartedAndNotLifecycling(() -> displayablesById.get(id));
        if (displayable == null) {
            response.getWriter().write("{\"error\":\"unknown id\"}");
            response.setStatus(404);
            return;
        }
        AsyncContext asyncContext = request.startAsync();
        displayable.toDto()
                   .whenCompleteAsync((dto, throwable) -> {
                       try {
                           if (throwable != null) {
                               logger.warn("Displayable {} failed to generate DTO", id, throwable);
                           } else {
                               MAPPER.writeValue(response.getWriter(), Map.of("id", id, "dto", dto));
                           }
                       } catch (IOException e) {
                           logger.info("Failed to write response for displayable DTO {}", id, e);
                       } finally {
                           asyncContext.complete();
                       }
                   }, executor);
    }

    @Override
    public void handleDownload(HttpServletRequest request, HttpServletResponse response) {
        if (!"/download".equals(request.getPathInfo())) {
            asUnchecked(() -> {
                response.setStatus(404);
                response.getWriter().print("Unknown path: " + request.getPathInfo());
            });
            return;
        }
        whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
            AsyncContext asyncContext = request.startAsync();
            asyncContext.start(() -> whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
                var displayableId = request.getParameter("displayableId");
                var displayable = displayablesById.get(displayableId);
                if (displayable == null) {
                    response.setStatus(404);
                    response.getWriter().print("No displayable found with id='" + displayableId + "'");
                    asyncContext.complete();
                } else {
                    String downloadId = request.getParameter("downloadId");
                    if (downloadId == null) {
                        response.setStatus(404);
                        response.getWriter().print("Missing 'downloadId' parameter");
                        asyncContext.complete();
                    } else {
                        displayable.handleDownload(downloadId, response)
                                   .whenCompleteAsync((_, throwable) -> {
                                       try {
                                           if (throwable != null) {
                                               logger.debug("Displayable {} download {} failed", displayableId, downloadId, throwable);
                                               response.setStatus(400);
                                               response.getWriter().write(humanReadableMessage(throwable));
                                           }
                                       } catch (IOException e) {
                                           logger.warn("Error while sending error response for displayable {}", displayableId, e);
                                       } finally {
                                           asyncContext.complete();
                                       }
                                   }, executor);
                    }
                }
            })));
        }));
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

        Closeable streamCloseCallback = idempotent(onStreamClosed::run);
        @SuppressWarnings("resource")
        var client = new SseClient(asyncContext, clientId, clientIdSeqNum);
        logger.debug("[SSE {}] created", clientId);
        executor.execute(() -> {
            sseClients.add(client);
            client.init();
            logger.debug("[SSE {}] delivering initial image", clientId);
            var targetClients = List.of(client);
            sendDisplayablesSnapshotTo(targetClients);
            sendOptionSnapshotTo(targetClients);
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
                                     clientId, (StringBuilderFormattable) buffer -> appendHumanReadableMessage(event.getThrowable(), Appender.wrap(buffer)));
                        removeClient();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                        logger.debug("[SSE {}] onStartAsync", clientId);
                    }

                    private void removeClient() {
                        if (isStarted()) {
                            executor.execute(() -> {
                                sseClients.remove(client);
                                Closeable.closeIfNotNull(streamCloseCallback);
                            });
                        }
                    }
                });
            } catch (RuntimeException e) {
                logger.debug("[SSE {}] asyncContext.addListener failed", clientId, e);
                closeStreamAndClient(client, streamCloseCallback);
            }
        });
        return idempotent(() -> executor.execute(() -> closeStreamAndClient(client, streamCloseCallback)));
    }

    private void closeStreamAndClient(SseClient client, Closeable streamClosed) {
        client.close();
        sseClients.remove(client);
        Closeable.closeIfNotNull(streamClosed);
    }

    private void sendDisplayablesSnapshotTo(List<SseClient> targetClients) {
        whenStartedAndNotLifecycling(() -> ImmutableList.copyOf(displayablesById.values()))
                .forEach(displayable -> sendDisplayableUpdate(displayable, targetClients));
    }

    private void sendDisplayableUpdate(Displayable displayable) {
        sendDisplayableUpdate(displayable, sseClients);
    }

    private void sendDisplayableUpdate(Displayable displayable, Iterable<SseClient> clients) {
        displayable.toDto().whenCompleteAsync((displayableDto, throwable) -> {
            if (throwable != null) {
                logger.warn("Displayable {} failed to generate DTO", displayable.getId(), throwable);
                return;
            }
            broadcastSse("displayable-update", Map.of("id", displayable.getId(), "dto", displayableDto), clients);
        }, executor);
    }

    private void notifyOptionSnapshotChanged() {
        optionSnapshotStabiliser.accept(null);
    }

    private void broadcastOptionSnapshot() {
        sendOptionSnapshotTo(sseClients);
    }

    private static String toDomId(String raw) {
        return TAB_NAME_TO_ID_CONVERSION_PATTERN.matcher(raw).replaceAll("-");
    }

    private static void broadcastSse(String eventName, Object data, Iterable<SseClient> clients) {
        for (SseClient client : ImmutableList.copyOf(clients)) {
            client.sendEvent(eventName, data);
        }
    }

    private void sendSseHeartbeat() {
        for (SseClient client : sseClients) {
            client.sendEvent("ping", null);
        }
    }

    @SuppressWarnings("HardcodedLineSeparator")
    private static final class SseClient implements Closeable {
        private final AsyncContext asyncContext;
        private final ServletOutputStream out;
        private final int clientIdSeqNum;
        private final String clientId;

        SseClient(AsyncContext asyncContext, String clientId, int clientIdSeqNum) throws IOException {
            this.asyncContext = checkNotNull(asyncContext);
            this.clientId = checkNotNull(clientId);
            out = asyncContext.getResponse().getOutputStream();
            this.clientIdSeqNum = clientIdSeqNum;
        }

        private void init() {
            try {
                logger.debug("[SSE {}] init", clientId);
                out.print("retry: 3000\n\n");
                // immediately inform the client of the server-assigned sequence number
                sendEvent("hello", Map.of("clientIdSeqNum", clientIdSeqNum));
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        private void sendEvent(String eventName, Object data) {
            try {
                logger.debug("[SSE {}] send event {}, {}", clientId, eventName, data);
                out.print("event: ");
                out.print(eventName);
                out.print('\n');
                out.print("data: ");
                MAPPER.writeValue(out, data);
                out.print("\n\n");
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        @Override
        public void close() {
            try {
                logger.debug("[SSE {}] closed", clientId);
                asyncContext.complete();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ThreadSuffix {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface OptionsStabilisationDelay {
    }

}
