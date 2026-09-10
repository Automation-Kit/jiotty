package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.exc.StreamWriteException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.ThrowingConsumer;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionRejectionReasons;
import net.yudichev.jiotty.user.ui.options.OptionValueRejectedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.getCausalChain;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;
import static net.yudichev.jiotty.user.ui.UIServerModule.Dependency;
import static net.yudichev.jiotty.user.ui.UIServerModule.SubjectId;

/// Handles `POST /ui/api/options` — option form submission. Responds with 405 on any other HTTP method.
///
/// A rejected value comes back as a JSON body naming the case — see [OptionValueRejectedException] — never as exception text, because an option value carries
/// whatever the user typed into the form, up to a third-party account password.
public final class OptionsPostHandler extends BaseLifecycleComponent implements ApiPathHandler {
    static final String PATH = "/options";
    private static final Logger logger = LogManager.getLogger(OptionsPostHandler.class);

    private static final ObjectWriter REJECTION_WRITER = UIJson.createWriterFor(Rejection.class);

    private final OptionRegistry registry;
    private final AdminAlertService alertService;
    private final Provider<SchedulingExecutor> executorProvider;
    private final String userId;

    private SchedulingExecutor executor;

    @Inject
    public OptionsPostHandler(OptionRegistry registry,
                              @Dependency AdminAlertService alertService,
                              @UIExecutor Provider<SchedulingExecutor> executorProvider,
                              @SubjectId String userId) {
        this.registry = checkNotNull(registry, "registry");
        this.alertService = checkNotNull(alertService, "alertService");
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
        this.userId = checkNotNull(userId, "userId");
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
                try {
                    var optionKey = request.getParameter("name");
                    if (optionKey == null) {
                        throw OptionValueRejectedException.of(OptionRejectionReasons.MISSING_OPTION_NAME);
                    }
                    String value = request.getParameter("value");
                    if (logger.isDebugEnabled()) {
                        // The value is whatever the form held, which for an integration is a JSON blob carrying that provider's credentials; log its length.
                        logger.debug("[{}] Option {} submitted, valueLength={}", userId, optionKey, value == null ? -1 : value.length());
                    }
                    Option<?> option = registry.find(optionKey)
                                               .orElseThrow(() -> {
                                                   logger.info("[{}] Rejecting submission of unknown option {}", userId, optionKey);
                                                   return OptionValueRejectedException.of(OptionRejectionReasons.UNKNOWN_OPTION);
                                               });
                    option.onFormSubmit(Optional.ofNullable(value))
                          .whenCompleteAsync((responseData, throwable) -> answer(asyncContext, response, resp -> {
                              if (throwable != null) {
                                  writeOptionFormPostFailure(resp, throwable);
                              } else {
                                  resp.setCharacterEncoding("utf-8");
                                  resp.setContentType(CONTENT_TYPE_JSON);
                                  UIJson.WRITER.writeValue(resp.getOutputStream(), responseData);
                              }
                          }), executor);
                } catch (
                    // Any synchronous failure on the submit pipeline (validation, the registered Option's own
                    // onFormSubmit throwing, the registry lookup, …) must still complete the AsyncContext and answer; the broad catch is deliberate
                        @SuppressWarnings("OverlyBroadCatchBlock") RuntimeException e) {
                    answer(asyncContext, response, resp -> writeOptionFormPostFailure(resp, e));
                }
            });
        }));
    }

    /// Writes one answer and completes the request whatever happens. A response of ours that will not serialise is a bug here and nobody's to retry, so it
    /// alerts; a client that hung up mid-response is ordinary traffic.
    private void answer(AsyncContext asyncContext, HttpServletResponse response, ThrowingConsumer<HttpServletResponse, IOException> write) {
        try {
            write.accept(response);
        } catch (StreamWriteException | DatabindException e) {
            alertService.raise(WARNING, "Option response could not be serialised", logger, e);
        } catch (IOException e) {
            logger.debug("[{}] Client went away while writing an option response", userId, e);
        } finally {
            asyncContext.complete();
        }
    }

    /// Answers a failed submission. A value the option refused is the user's to correct, so it comes back 400 naming the case; anything else is a fault on
    /// our side, and answering that 400 too would tell the user to fix a value that was never the problem while no one is told the server broke.
    private void writeOptionFormPostFailure(HttpServletResponse response, Throwable throwable) throws IOException {
        Rejection rejection = rejectionOf(throwable);
        if (rejection == null) {
            alertService.raise(WARNING, "Option form submission failed", logger, throwable);
            ErrorResponse.write(response, INTERNAL_SERVER_ERROR_500, ErrorResponse.INTERNAL_ERROR);
            return;
        }
        // Expected, and named by the response — the stack would say nothing the reason does not.
        logger.info("[{}] Option submission rejected: {}", userId, rejection.reason());
        response.setCharacterEncoding("utf-8");
        response.setContentType(CONTENT_TYPE_JSON);
        response.setStatus(BAD_REQUEST_400);
        REJECTION_WRITER.writeValue(response.getOutputStream(), rejection);
    }

    /// The rejection a client is told about, or `null` where the failure was not one — no exception message reaches the client either way.
    private static @Nullable Rejection rejectionOf(Throwable throwable) {
        // The completion stages wrap what they carry, so the rejection arrives nested rather than as itself.
        for (Throwable cause : getCausalChain(throwable)) {
            if (cause instanceof OptionValueRejectedException rejection) {
                return new Rejection(rejection.reason(), rejection.params());
            }
        }
        return null;
    }

    private record Rejection(String reason, Map<String, Object> params) {}
}
