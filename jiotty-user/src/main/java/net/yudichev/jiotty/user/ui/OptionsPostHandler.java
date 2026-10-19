package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.NullNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.JsonBody;
import net.yudichev.jiotty.user.ui.io.ReceivedBody;
import net.yudichev.jiotty.user.ui.io.RequestBodies;
import net.yudichev.jiotty.user.ui.options.FormSubmitResult;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionRejection;
import net.yudichev.jiotty.user.ui.options.OptionRejectionReasons;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.PAYLOAD_TOO_LARGE_413;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;
import static net.yudichev.jiotty.user.ui.UIServerModule.Dependency;
import static net.yudichev.jiotty.user.ui.UIServerModule.SubjectId;

/// Handles `POST /ui/api/options` — option form submission. Responds with 405 on any other HTTP method.
///
/// A rejected value comes back as a JSON body naming the case — see [OptionRejection] — never as exception text, because an option value carries whatever the
/// user typed into the form, up to a third-party account password.
public final class OptionsPostHandler extends BaseLifecycleComponent implements ApiPathHandler {
    /// The form field naming the option submitted.
    public static final String NAME_FIELD = "name";
    /// The form field carrying the value submitted; a form without it clears the option.
    public static final String VALUE_FIELD = "value";
    static final String PATH = "/options";
    @VisibleForTesting
    static final String TIMED_OUT_ALERT_TITLE = "Option form submission timed out";
    /// The longest submission read: an integration's value is a JSON blob of that provider's credentials, a few KiB at most.
    @VisibleForTesting
    static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Logger logger = LogManager.getLogger(OptionsPostHandler.class);
    private static final String SUBMISSION_TASK_NAME = "option submission";
    private static final ObjectWriter REJECTION_WRITER = UIJson.createWriterFor(OptionRejection.class);
    /// The refusals this handler makes before any option is reached. None carries anything of the request, so one instance of each serves every request.
    private static final Answer MISSING_OPTION_NAME_ANSWER = createRejectionAnswer(OptionRejection.of(OptionRejectionReasons.MISSING_OPTION_NAME));
    private static final Answer UNKNOWN_OPTION_ANSWER = createRejectionAnswer(OptionRejection.of(OptionRejectionReasons.UNKNOWN_OPTION));
    private static final Answer INVALID_BODY_ANSWER = ErrorResponse.createAnswer(BAD_REQUEST_400, ErrorResponse.INVALID_BODY);
    private static final Answer BODY_TOO_LARGE_ANSWER = ErrorResponse.createAnswer(PAYLOAD_TOO_LARGE_413, ErrorResponse.BODY_TOO_LARGE);
    /// The answer to a submission that cleared its option: a 200 whose body is JSON `null`.
    private static final Answer CLEARED_OPTION_ANSWER = Answer.of(OK_200, new JsonBody(UIJson.WRITER, NullNode.getInstance()));

    private final OptionRegistry registry;
    private final AdminAlertService alertService;
    private final Consumer<Throwable> encodingFailureHandler;
    private final Deadline deadline;
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
        this.userId = checkNotNull(userId, "userId");
        encodingFailureHandler = failure -> alertService.raise(WARNING, "Option response could not be serialised", logger, failure);
        deadline = HandlerDeadlines.create(alertService, logger, userId, TIMED_OUT_ALERT_TITLE, encodingFailureHandler);
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
        whenStartedAndNotLifecycling(() -> {
            var exchange = AsyncExchange.start(alertService, request, response, deadline);
            // Decoded on the container thread that read the body's last bytes, so its pooled bytes are released before the submission reaches the executor.
            RequestBodies.receive(exchange, MAX_BODY_BYTES)
                         .whenComplete((body, readFailure) -> {
                             if (readFailure != null) {
                                 if (logger.isDebugEnabled()) {
                                     logger.debug("[{}] Could not read an option submission: {}", userId, humanReadableMessageFormattable(readFailure));
                                 }
                                 answer(exchange, INVALID_BODY_ANSWER);
                                 return;
                             }
                             try {
                                 ReceivedBody<OptionSubmission> received;
                                 try {
                                     received = body.parseWith(OptionsPostHandler::decodeSubmission);
                                 } finally {
                                     body.release();
                                 }
                                 switch (received) {
                                     case ReceivedBody.Parsed(OptionSubmission submission) -> submitOnExecutor(exchange, submission);
                                     case ReceivedBody.Empty<OptionSubmission> _ -> submitOnExecutor(exchange, OptionSubmission.EMPTY);
                                     case ReceivedBody.TooLarge<OptionSubmission> _ -> answer(exchange, BODY_TOO_LARGE_ANSWER);
                                     case ReceivedBody.Malformed<OptionSubmission> _ -> {
                                         // A fixed reason: the decoder's message quotes the characters it could not decode, which are the submitted value's.
                                         logger.debug("[{}] Malformed option submission: a form field does not decode", userId);
                                         answer(exchange, INVALID_BODY_ANSWER);
                                     }
                                 }
                             } catch (RuntimeException e) {
                                 // The executor rejecting the submission, or the decoding failing in a way it does not expect, must still answer the exchange.
                                 answer(exchange, alertAndCreateServerFaultAnswer(e));
                             }
                         });
        });
    }

    /// Decodes an `application/x-www-form-urlencoded` body, keeping the first [#NAME_FIELD] and [#VALUE_FIELD] fields it carries.
    ///
    /// @throws IOException if a field's escapes do not decode
    private static OptionSubmission decodeSubmission(InputStream body) throws IOException {
        // Held whole, as URLDecoder decodes a String only; the body is capped at MAX_BODY_BYTES.
        String form = new String(body.readAllBytes(), UTF_8);
        String name = null;
        String value = null;
        try {
            for (String field : Splitter.on('&').omitEmptyStrings().split(form)) {
                int separator = field.indexOf('=');
                String fieldName = URLDecoder.decode(separator < 0 ? field : field.substring(0, separator), UTF_8);
                String fieldValue = separator < 0 ? "" : URLDecoder.decode(field.substring(separator + 1), UTF_8);
                if (NAME_FIELD.equals(fieldName) && name == null) {
                    name = fieldValue;
                } else if (VALUE_FIELD.equals(fieldName) && value == null) {
                    value = fieldValue;
                }
            }
        } catch (IllegalArgumentException e) {
            //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException the decoder's message quotes characters of the field, which may be a credential
            throw new IOException("a form field does not decode");
        }
        return new OptionSubmission(name, value);
    }

    /// Runs [#submit] on this handler's executor.
    private void submitOnExecutor(AsyncExchange exchange, OptionSubmission submission) {
        executor.execute(SUBMISSION_TASK_NAME, () -> {
            try {
                submit(exchange, submission);
            } catch (RuntimeException e) {
                // The registry lookup throws while the app is stopping, and an option's onFormSubmit may throw: the exchange is answered all the same.
                answer(exchange, alertAndCreateServerFaultAnswer(e));
            }
        });
    }

    private void submit(AsyncExchange exchange, OptionSubmission submission) {
        String optionKey = submission.name();
        if (optionKey == null) {
            logger.info("[{}] Rejecting a submission that names no option", userId);
            answer(exchange, MISSING_OPTION_NAME_ANSWER);
            return;
        }
        String value = submission.value();
        if (logger.isDebugEnabled()) {
            // The value is whatever the form held, which for an integration is a JSON blob carrying that provider's credentials; log its length.
            logger.debug("[{}] Option {} submitted, valueLength={}", userId, optionKey, value == null ? -1 : value.length());
        }
        Option<?> option = registry.find(optionKey).orElse(null);
        if (option == null) {
            logger.info("[{}] Rejecting submission of unknown option {}", userId, optionKey);
            answer(exchange, UNKNOWN_OPTION_ANSWER);
            return;
        }
        option.onFormSubmit(Optional.ofNullable(value))
              .whenCompleteAsync((result, throwable) -> {
                  if (throwable != null) {
                      answer(exchange, alertAndCreateServerFaultAnswer(throwable));
                  } else {
                      answer(exchange, switch (result) {
                          case FormSubmitResult.Accepted(Object responseData) when responseData == null -> CLEARED_OPTION_ANSWER;
                          case FormSubmitResult.Accepted(Object responseData) -> Answer.of(OK_200, new JsonBody(UIJson.WRITER, responseData));
                          case FormSubmitResult.Rejected(OptionRejection rejection) -> rejectSubmission(rejection);
                      });
                  }
              }, executor);
    }

    /// Writes `answer` on a container thread, raising an alert when its body cannot be encoded: a response of ours that will not serialise is a bug, not a
    /// client error.
    private void answer(AsyncExchange exchange, Answer answer) {
        exchange.answer(answer, encodingFailureHandler);
    }

    /// Logs a value the option rejected and creates its answer.
    private Answer rejectSubmission(OptionRejection rejection) {
        // Expected, and named by the response — a stack would say nothing the reason does not.
        logger.info("[{}] Option submission rejected: {}", userId, rejection.reason());
        return createRejectionAnswer(rejection);
    }

    /// Creates the 400 answer for a rejected submission, naming the case and carrying its bounds as data.
    private static Answer createRejectionAnswer(OptionRejection rejection) {
        return Answer.of(BAD_REQUEST_400, new JsonBody(REJECTION_WRITER, rejection));
    }

    /// Raises an alert for a failure on this server's side and returns the 500 answer for it.
    private Answer alertAndCreateServerFaultAnswer(Throwable throwable) {
        alertService.raise(WARNING, "Option form submission failed", logger, throwable);
        return ErrorResponse.SERVER_FAULT;
    }

    /// One form submission's fields.
    ///
    /// @param name  the key of the option submitted; `null` when the form names none
    /// @param value the value submitted; `null` when the form carries none, which clears the option
    private record OptionSubmission(@Nullable String name, @Nullable String value) {
        static final OptionSubmission EMPTY = new OptionSubmission(null, null);
    }
}
