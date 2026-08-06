package net.yudichev.jiotty.user.ui;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.user.ui.options.Option;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;

/// Handles `POST /ui/api/options` — option form submission. Responds with 405 on any other HTTP method.
public final class OptionsPostHandler extends BaseLifecycleComponent implements ApiPathHandler {
    static final String PATH = "/options";
    private static final Logger logger = LogManager.getLogger(OptionsPostHandler.class);

    private final OptionRegistry registry;
    private final Provider<SchedulingExecutor> executorProvider;

    private SchedulingExecutor executor;

    @Inject
    public OptionsPostHandler(OptionRegistry registry, @UIExecutor Provider<SchedulingExecutor> executorProvider) {
        this.registry = checkNotNull(registry, "registry");
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
    }

    @Override
    public String pathPrefix() {
        return PATH;
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) {
        if (!"POST".equals(request.getMethod())) {
            response.setStatus(METHOD_NOT_ALLOWED_405);
            return;
        }
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
                    var optionKey = request.getParameter("name");
                    checkArgument(optionKey != null, "Missing name parameter");
                    Option<?> option = registry.find(optionKey)
                                               .orElseThrow(() -> new IllegalArgumentException("Unknown optionKey: " + optionKey));
                    option.onFormSubmit(Optional.ofNullable(request.getParameter("value")))
                          .whenCompleteAsync((responseData, throwable) -> {
                              try {
                                  response.setCharacterEncoding("utf-8");
                                  if (throwable != null) {
                                      writeOptionFormPostFailure(response, throwable);
                                  } else {
                                      response.setContentType("application/json");
                                      UIJson.WRITER.writeValue(response.getWriter(), responseData);
                                  }
                              } catch (IOException e) {
                                  logger.warn("Value rendering failed for option {}", option, e);
                              } finally {
                                  asyncContext.complete();
                              }
                          }, executor);
                } catch (
                    // Any synchronous failure on the submit pipeline (validation, the registered Option's own
                    // onFormSubmit throwing, the registry lookup, …) must still complete the AsyncContext and reply 400; the broad catch is deliberate
                        @SuppressWarnings("OverlyBroadCatchBlock") RuntimeException e) {
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
        response.setStatus(BAD_REQUEST_400);
        response.getWriter().write(humanReadableMessage(throwable));
    }
}
