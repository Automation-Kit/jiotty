package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.throttling.ThrottlingConsumer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.forCloseables;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.Closeable.noop;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

final class UIServerImpl extends BaseLifecycleComponent implements UIServer {
    private static final Logger logger = LoggerFactory.getLogger(UIServerImpl.class);
    private static final Pattern TAB_NAME_TO_ID_CONVERSION_PATTERN = Pattern.compile("[^A-Za-z0-9_-]");
    private static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory()).registerModule(new JavaTimeModule());

    private final Map<String, Displayable> displayablesById = new LinkedHashMap<>();
    private final Map<String, List<Option<?>>> optionsByTabName = new HashMap<>();
    private final Map<String, Option<?>> optionsByKey = new HashMap<>();
    private final OptionPersistence persistence;
    private final Server server;
    private final List<Closeable> optionsPersistenceRegistrations = new ArrayList<>();
    private final ExecutorFactory executorFactory;
    private final Set<SseClient> sseClients = new HashSet<>();
    private final AtomicInteger sseClientIdGenerator = new AtomicInteger();

    private SchedulingExecutor executor;
    private Closeable sseHeartbeat = Closeable.noop();

    @Inject
    UIServerImpl(OptionPersistence persistence, ExecutorFactory executorFactory) {
        this.persistence = checkNotNull(persistence);
        server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setFormEncodedMethods("POST");

        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(UIServerModule.LISTEN_PORT);
        server.addConnector(connector);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath(UIServerModule.PATH_ROOT);
        String styleCssPath = requireNonNull(getClass().getResource("/uiserver/wwwroot/style.css")).toString();
        servletContextHandler.setResourceBase(styleCssPath.substring(0, styleCssPath.lastIndexOf('/')));

        // Single page app is served from /options (GET)
        servletContextHandler.addServlet(new ServletHolder(new OptionsServlet()), UIServerModule.SUB_PATH_OPTIONS);

        // Displayables - downloads
        servletContextHandler.addServlet(new ServletHolder(new DownloadServlet()), "/displayables/*");

        // JSON API
        servletContextHandler.addServlet(new ServletHolder(new ApiServlet()), "/api/*");

        // Lastly, the default servlet for resource content (always needed)
        var resourceServletHolder = new ServletHolder("default", DefaultServlet.class);
        resourceServletHolder.setInitParameter("dirAllowed", "false");
        servletContextHandler.addServlet(resourceServletHolder, "/");

        server.setHandler(new HandlerList(servletContextHandler, new DefaultHandler()));
        this.executorFactory = checkNotNull(executorFactory);
    }

    @Override
    public Closeable registerDisplayable(Displayable displayable) {
        return whenStartedAndNotLifecycling(() -> {
            checkArgument(displayablesById.putIfAbsent(displayable.getId(), displayable) == null,
                          "Displayable with id '%s' is already registered", displayable.getId());
            Closeable dataSubscription;
            @Nullable ThrottlingConsumer<Void> throttle;
            if (displayable.supportsData()) {
                throttle = new ThrottlingConsumer<>(executor, Duration.ofSeconds(1), _ -> onNewData(displayable));
                dataSubscription = displayable.subscribeForUpdates(() -> throttle.accept(null));
            } else {
                dataSubscription = noop();
                throttle = null;
            }
            logger.info("Registered displayable {} with title {}", displayable, displayable.getDisplayName());
            // deliver image to existing SSE clients
            onNewData(displayable);
            return idempotent(() -> whenStartedAndNotLifecycling(() -> {
                if (displayablesById.remove(displayable.getId(), displayable)) {
                    Closeable.closeSafelyIfNotNull(logger, throttle, dataSubscription);
                    logger.info("Unregistered displayable {} with title {}", displayable, displayable.getDisplayName());
                }
            }));
        });
    }

    private void onNewData(Displayable displayable) {
        // executor thread
        displayable.toDto().whenCompleteAsync(
                (displayableDto, throwable) -> {
                    if (throwable != null) {
                        logger.warn("Displayable {} failed to generate DTO", displayable.getId(), throwable);
                        return;
                    }
                    try {
                        String json = MAPPER.writeValueAsString(Map.of("id", displayable.getId(), "dto", displayableDto));
                        broadcastSse("displayable-update", json);
                    } catch (@SuppressWarnings("OverlyBroadCatchBlock") IOException e) {
                        logger.warn("Failed to serialize update of displayable {}", displayable.getId(), e);
                    }

                },
                executor);
    }

    @Override
    public Closeable registerOption(Option<?> option) {
        return whenNotLifecycling(() -> {
            checkArgument(!optionsByKey.containsKey(option.meta().key()), "Option for key %s already registered: %s", option.meta().key(), option);
            persistence.load(option); // may throw, so do not actually register until this succeeds

            optionsByKey.put(option.meta().key(), option);
            List<Option<?>> options = getOptionsForTab(option.meta().tabName());
            options.add(option);
            options.sort(comparing(Option::getFormOrder));
            Closeable persistenceRegistration = option.addChangeListener(persistence::save);
            optionsPersistenceRegistrations.add(persistenceRegistration);
            logger.info("Registered option {}", option.meta().key());
            return idempotent(() -> whenNotLifecycling(() -> {
                options.remove(option);
                optionsByKey.remove(option.meta().key());
                Closeable.closeIfNotNull(persistenceRegistration);
                optionsPersistenceRegistrations.remove(persistenceRegistration);
                logger.info("Unregistered option {}", option.meta().key());
            }));
        });
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("UI");
        asUnchecked(server::start);

        // periodic heartbeat for SSE to keep connections alive through proxies
        sseHeartbeat = executor.scheduleAtFixedRate(Duration.ofSeconds(15), this::sendSseHeartbeat);
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, sseHeartbeat);
        closeSafelyIfNotNull(logger, server::stop);
        closeSafelyIfNotNull(logger, forCloseables(optionsPersistenceRegistrations), executor);
    }

    private List<Option<?>> getOptionsForTab(String tabName) {
        return optionsByTabName.computeIfAbsent(tabName, _ -> new ArrayList<>());
    }

    private static String toDomId(String raw) {
        return TAB_NAME_TO_ID_CONVERSION_PATTERN.matcher(raw).replaceAll("-");
    }


    private void writeOptionsJson(HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("utf-8");
        resp.setContentType("application/json");
        Map<String, List<Option<?>>> optionsByTabNameCopy = whenStartedAndNotLifecycling(() -> {
            var copy = new HashMap<>(optionsByTabName);
            copy.replaceAll((_, options) -> new ArrayList<>(options));
            return copy;
        });
        var tabs = new ArrayList<Map<String, Object>>();
        for (var entry : optionsByTabNameCopy.entrySet()) {
            String tabName = entry.getKey();
            String tabId = toDomId(tabName);
            List<Option<?>> options = entry.getValue();
            var optionDtos = new ArrayList<OptionDtos.OptionDto>(options.size());
            for (var option : options) {
                optionDtos.add(option.toDto());
            }
            tabs.add(Map.of("id", tabId,
                            "name", tabName,
                            "options", optionDtos));
        }
        var body = Map.of("tabs", tabs);
        MAPPER.writeValue(resp.getWriter(), body);
    }

    private void writeDisplayablesListJson(HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("utf-8");
        resp.setContentType("application/json");
        Map<String, Displayable> displayablesByIdCopy = whenStartedAndNotLifecycling(() -> new HashMap<>(displayablesById));
        var items = new ArrayList<Map<String, Object>>(displayablesByIdCopy.size());
        displayablesByIdCopy.forEach((id, displayable) -> {
            if (displayable.visible()) {
                items.add(Map.of("id", id, "name", displayable.getDisplayName(), "safeId", toDomId(id)));
            } else {
                // hidden: do not show a sub-tab for this displayable
            }
        });
        var body = Map.of("items", items);
        MAPPER.writeValue(resp.getWriter(), body);
    }

    private void writeDisplayableItemJson(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setCharacterEncoding("utf-8");
        resp.setContentType("application/json");
        String id = req.getParameter("id");
        if (id == null || id.isBlank()) {
            resp.getWriter().write("{\"error\":\"missing id\"}");
            resp.setStatus(400);
            return;
        }
        Displayable displayable = whenStartedAndNotLifecycling(() -> displayablesById.get(id));
        if (displayable == null) {
            resp.getWriter().write("{\"error\":\"unknown id\"}");
            resp.setStatus(404);
            return;
        }
        AsyncContext asyncContext = req.startAsync();
        displayable.toDto()
                   .whenCompleteAsync((dto, throwable) -> {
                       try {
                           if (throwable != null) {
                               logger.warn("Displayable {} failed to generate DTO", id, throwable);
                           } else {
                               var body = Map.of("id", id, "dto", dto);
                               MAPPER.writeValue(resp.getWriter(), body);
                           }
                       } catch (IOException e) {
                           logger.info("Failed to write response for displayable DTO {}", id, e);
                       } finally {
                           asyncContext.complete();
                       }
                   });
    }

    private static String optionPostResponse(Option<?> option) {
        var dto = option.toDto();
        return switch (dto) {
            case OptionDtos.Checkbox checkbox -> Boolean.toString(checkbox.checked());
            case OptionDtos.MultiSelect multiSelect -> String.join(",", multiSelect.selectedIds());
            case OptionDtos.Duration duration -> duration.valueHuman() == null ? "" : duration.valueHuman();
            case OptionDtos.Time time -> time.value() == null ? "" : time.value();
            case OptionDtos.Select select -> select.value() == null ? "" : select.value();
            case OptionDtos.TextArea textArea -> textArea.value() == null ? "" : textArea.value();
            case OptionDtos.Text text -> text.value() == null ? "" : text.value();
            case OptionDtos.Chat chat -> chat.historyText() == null ? "" : chat.historyText();
            case null -> "";
        };
    }

    private void broadcastSse(String eventName, String jsonData) {
        // executor thread
        for (SseClient client : sseClients) {
            client.sendEvent(eventName, jsonData);
        }
    }

    private void sendSseHeartbeat() {
        // executor thread
        for (SseClient client : sseClients) {
            client.ping();
        }
    }

    private void startDisplayablesSse(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        int clientIdSeqNum = sseClientIdGenerator.incrementAndGet();
        String clientId = clientIdSeqNum + "/" + req.getRemoteHost() + ":" + req.getRemotePort();
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(0);
        resp.setCharacterEncoding("utf-8");
        resp.setContentType("text/event-stream");
        resp.setHeader("Cache-Control", "no-cache");
        resp.setHeader("Connection", "keep-alive");
        // prevents proxy buffering after client disconnected when behind Nginx
        resp.setHeader("X-Accel-Buffering", "no");
        // include server-generated client sequence number for client-side diagnostics
        resp.setHeader("X-Client-Id-Seq-Num", String.valueOf(clientIdSeqNum));
        // make sure headers are sent
        resp.flushBuffer();

        @SuppressWarnings("resource")
        var client = new SseClient(asyncContext, clientId, clientIdSeqNum);
        logger.debug("[SSE {}] created", clientId);
        executor.execute(() -> {
            sseClients.add(client);
            client.init();

            // deliver image to the new client
            logger.debug("[SSE {}] delivering initial image", clientId);
            whenStartedAndNotLifecycling(() -> List.copyOf(displayablesById.values())).forEach(this::onNewData);

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
                        logger.debug("[SSE {}] onError", clientId);
                        removeClient();
                    }

                    @Override
                    public void onStartAsync(AsyncEvent event) {
                        logger.debug("[SSE {}] onStartAsync", clientId);
                        // no-op
                    }

                    private void removeClient() {
                        executor.execute(() -> sseClients.remove(client));
                    }
                });
            } catch (RuntimeException e) {
                logger.debug("[SSE {}] asyncContext.addListener failed", clientId, e);
                client.close();
                sseClients.remove(client);
            }
        });
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
                sendEvent("hello", "{\"clientIdSeqNum\":" + clientIdSeqNum + "}");
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        private void sendEvent(String eventName, String data) {
            try {
                logger.debug("[SSE {}] send event {}, {}", clientId, eventName, data);
                out.print("event: ");
                out.print(eventName);
                out.print('\n');
                out.print("data: ");
                out.print(data);
                out.print("\n\n");
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        public void ping() {
            try {
                logger.debug("[SSE {}] ping", clientId);
                out.print("event: ping\n");
                out.print("data: {}\n\n");
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

    private class OptionsServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.sendRedirect(UIServerModule.PATH_ROOT + "/index.html");
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) {
            whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.start(
                        () -> {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Form parameters: {}",
                                             request.getParameterMap().entrySet().stream()
                                                    .map(e -> e.getKey() + '=' + Arrays.toString(e.getValue()))
                                                    .toList());
                            }
                            whenStartedAndNotLifecycling(
                                    () -> {
                                        var optionKey = request.getParameter("name");
                                        checkArgument(optionKey != null, "Missing name parameter");
                                        Option<?> option = optionsByKey.get(optionKey);
                                        checkArgument(option != null, "Unknown optionKey: %s, known options are: %s", optionKey, optionsByKey.keySet());
                                        option.onFormSubmit(Optional.ofNullable(request.getParameter("value")))
                                              .whenComplete((_, throwable) -> {
                                                  if (throwable != null) {
                                                      logger.info("Option form submission failed", throwable);
                                                      try {
                                                          response.setCharacterEncoding("utf-8");
                                                          response.setContentType("text/plain");
                                                          response.setStatus(400);
                                                          response.getWriter().write(humanReadableMessage(throwable));
                                                      } catch (IOException e) {
                                                          logger.warn("Sending error failed for option {}", option, e);
                                                      }
                                                  } else {
                                                      try {
                                                          response.setCharacterEncoding("utf-8");
                                                          response.setContentType("text/plain");
                                                          String respBody = optionPostResponse(option);
                                                          response.getWriter().write(respBody);
                                                      } catch (IOException e) {
                                                          logger.warn("Value rendering failed for option {}", option, e);
                                                      }
                                                  }
                                                  asyncContext.complete();
                                              });
                                    });
                        });
            }));
        }
    }

    private class DownloadServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            if ("/download".equals(req.getPathInfo())) {
                handleDownload(req, resp);
            } else {
                resp.setStatus(404);
                resp.getWriter().print("Unknown path: " + req.getPathInfo());
            }
        }

        private void handleDownload(HttpServletRequest req, HttpServletResponse resp) {
            whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
                AsyncContext asyncContext = req.startAsync();
                asyncContext.start(() -> whenStartedAndNotLifecycling(() -> asUnchecked(() -> {
                    var displayableId = req.getParameter("displayableId");
                    var displayable = displayablesById.get(displayableId);
                    if (displayable == null) {
                        resp.setStatus(404);
                        resp.getWriter().print("No displayable found with id='" + displayableId + "'");
                        asyncContext.complete();
                    } else {
                        String downloadId = req.getParameter("downloadId");
                        if (downloadId == null) {
                            resp.setStatus(404);
                            resp.getWriter().print("Missing 'downloadId' parameter");
                            asyncContext.complete();
                        } else {
                            displayable.handleDownload(downloadId, resp)
                                       .whenCompleteAsync((_, e) -> { // go off the displayable thread not to do I/O on it
                                           try {
                                               if (e != null) {
                                                   logger.debug("Displayable {} download {} failed", displayableId, downloadId, e);
                                                   resp.setStatus(400);
                                                   asUnchecked(() -> resp.getWriter().print("Download failed: " + humanReadableMessage(e)));
                                               }
                                           } finally {
                                               asyncContext.complete();
                                           }
                                       }, executor);
                        }
                    }
                })));
            }));

        }

    }

    private class ApiServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String path = req.getPathInfo();
            switch (path) {
                case "/options" -> whenStartedAndNotLifecycling(() -> asUnchecked(() -> writeOptionsJson(resp)));
                case "/displayables" -> whenStartedAndNotLifecycling(() -> asUnchecked(() -> writeDisplayablesListJson(resp)));
                case "/displayables/item" -> whenStartedAndNotLifecycling(() -> asUnchecked(() -> writeDisplayableItemJson(req, resp)));
                case "/displayables/stream" -> whenStartedAndNotLifecycling(() -> asUnchecked(() -> startDisplayablesSse(req, resp)));
                case null, default -> {
                    resp.setCharacterEncoding("utf-8");
                    resp.setContentType("application/json");
                    resp.setStatus(404);
                    resp.getWriter().print("{\"error\":\"Unknown path\"}");
                }
            }
        }
    }
}