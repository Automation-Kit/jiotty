package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.ObjectWriter;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.BindingAnnotation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import net.yudichev.jiotty.common.rest.BearerTokens;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.AsyncExchange;
import net.yudichev.jiotty.user.ui.io.Deadline;
import net.yudichev.jiotty.user.ui.io.JsonBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.ERROR;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.WARNING;
import static net.yudichev.jiotty.common.rest.HttpStatuses.CONFLICT_409;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenAuthenticated;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenNotAuthenticated;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenNotAuthenticated.Reason;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenState;

final class AuthenticatedUIRequestAuthoriser implements UIRequestAuthoriser {
    @VisibleForTesting
    static final String MISSING_TOKEN_MESSAGE = "Missing or invalid Authorization bearer token";
    @VisibleForTesting
    static final String PRE_AUTH_RATE_LIMITED_MESSAGE = "Too many authentication attempts";
    @VisibleForTesting
    static final String PER_UID_RATE_LIMITED_MESSAGE = "Too many requests";
    @VisibleForTesting
    static final String AUTHORISATION_FAILED_MESSAGE = "Authorisation failed";
    @VisibleForTesting
    static final String AUTHORISATION_FAILED_ALERT_TITLE = "Failed to complete authorisation of a request";
    @VisibleForTesting
    static final String AUTHORISATION_TIMED_OUT_ALERT_TITLE = "Authorisation of a request timed out";
    /// How long a request waits for its token state: [HandlerDeadlines#TIMEOUT], the deadline of every handler's answer.
    @VisibleForTesting
    static final Duration AUTHORISATION_DEADLINE = HandlerDeadlines.TIMEOUT;
    @VisibleForTesting
    static final String AUTHORISE_TIMER = "ui_authorise_seconds";
    /// The [#AUTHORISE_TIMER] tag naming how the authorisation ended.
    @VisibleForTesting
    static final String TAG_OUTCOME = "outcome";
    @VisibleForTesting
    static final String OUTCOME_MISSING_TOKEN = "missing_token";
    @VisibleForTesting
    static final String OUTCOME_AUTHENTICATED = "authenticated";
    @VisibleForTesting
    static final String OUTCOME_REJECTED = "rejected";

    private static final Logger logger = LogManager.getLogger(AuthenticatedUIRequestAuthoriser.class);

    private static final ObjectWriter FAILURE_WRITER = UIJson.createWriterFor(AuthorisationFailure.class);
    private static final Answer MISSING_TOKEN_ANSWER = createFailureAnswer(statusFor(Reason.INVALID), Reason.INVALID, MISSING_TOKEN_MESSAGE);
    private static final Answer PRE_AUTH_RATE_LIMITED_ANSWER =
            createFailureAnswer(TOO_MANY_REQUESTS_429, Reason.TECHNICAL_FAILURE, PRE_AUTH_RATE_LIMITED_MESSAGE);
    private static final Answer VERIFY_SATURATED_ANSWER =
            createFailureAnswer(SERVICE_UNAVAILABLE_503, Reason.TECHNICAL_FAILURE, "Authentication temporarily unavailable");
    private static final Answer PER_UID_RATE_LIMITED_ANSWER =
            createFailureAnswer(TOO_MANY_REQUESTS_429, Reason.TECHNICAL_FAILURE, PER_UID_RATE_LIMITED_MESSAGE);
    private static final Answer AUTHORISATION_FAILED_ANSWER =
            createFailureAnswer(SERVICE_UNAVAILABLE_503, Reason.TECHNICAL_FAILURE, AUTHORISATION_FAILED_MESSAGE);

    private final UserTokenAuthoriser userTokenAuthoriser;
    private final PreAuthAdmissionControl admissionControl;
    private final PerUidRateLimiter perUidRateLimiter;
    private final AdminAlertService alertService;
    private final Consumer<Throwable> encodingFailureHandler;
    private final MeterRegistry meterRegistry;
    private final Timer missingTokenAuthTimer;
    private final Timer authenticatedAuthTimer;
    private final Timer rejectedAuthTimer;

    @Inject
    AuthenticatedUIRequestAuthoriser(@Dependency UserTokenAuthoriser userTokenAuthoriser,
                                     PreAuthAdmissionControl admissionControl,
                                     PerUidRateLimiter perUidRateLimiter,
                                     @Dependency AdminAlertService alertService,
                                     MeterRegistry meterRegistry) {
        this.userTokenAuthoriser = checkNotNull(userTokenAuthoriser, "userTokenAuthoriser");
        this.admissionControl = checkNotNull(admissionControl, "admissionControl");
        this.perUidRateLimiter = checkNotNull(perUidRateLimiter, "perUidRateLimiter");
        this.alertService = checkNotNull(alertService, "alertService");
        encodingFailureHandler = failure -> alertService.raise(ERROR, "An authorisation failure could not be encoded", logger, failure);
        this.meterRegistry = checkNotNull(meterRegistry, "meterRegistry");
        missingTokenAuthTimer = meterRegistry.timer(AUTHORISE_TIMER, TAG_OUTCOME, OUTCOME_MISSING_TOKEN);
        authenticatedAuthTimer = meterRegistry.timer(AUTHORISE_TIMER, TAG_OUTCOME, OUTCOME_AUTHENTICATED);
        rejectedAuthTimer = meterRegistry.timer(AUTHORISE_TIMER, TAG_OUTCOME, OUTCOME_REJECTED);
    }

    @Override
    public void authorise(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException {
        Timer.Sample authSample = Timer.start(meterRegistry);
        String token = BearerTokens.fromAuthorizationHeader(Optional.ofNullable(request.getHeader(AUTHORIZATION))).orElse(null);
        if (token == null) {
            authSample.stop(missingTokenAuthTimer);
            write(response, MISSING_TOKEN_ANSWER);
            return;
        }

        // Admission is decided before the request goes async, so a rejected caller costs one header parse.
        switch (admissionControl.tryAdmit(request)) {
            case RATE_LIMITED -> {
                authSample.stop(rejectedAuthTimer);
                write(response, PRE_AUTH_RATE_LIMITED_ANSWER);
                return;
            }
            case VERIFY_SATURATED -> {
                authSample.stop(rejectedAuthTimer);
                write(response, VERIFY_SATURATED_ANSWER);
                return;
            }
            case ADMITTED -> {
            }
        }

        // The permit bounds concurrent *verifications*, so it is returned as soon as the token state has been acted on. A downstream handler may keep the
        // request open for hours (an SSE stream does), which would tie up a verification slot for that whole time. The callback's `finally` covers every exit
        // it reaches; the exchange's end is the backstop for a request that never reaches the callback at all.
        var permit = new SingleUseInFlightPermit(admissionControl);
        var exchange = AsyncExchange.start(alertService, request, response, createDeadline(authSample));
        exchange.whenEnded(permit::release);
        try {
            userTokenAuthoriser.deliverTokenStateTo(token, state -> exchange.runOnContainerThread(() -> {
                try {
                    actOnTokenState(token, state, exchange, authSample);
                } finally {
                    permit.release();
                }
            }));
        } catch (RuntimeException e) {
            // Thrown before any token state could arrive: the request has gone async, so nothing else would ever answer it.
            failAuthorisation(exchange, e);
            permit.release();
        }
    }

    /// Writes `answer` on the servlet thread, before the request goes async.
    private static void write(HttpServletResponse response, Answer answer) throws IOException {
        response.setStatus(answer.status());
        answer.body().applyHeadersTo(response);
        answer.body().writeTo(response.getOutputStream());
    }

    /// Creates the deadline of one request's authorisation, past which it is answered as failed, with an alert: the token state it waits on has not arrived.
    private Deadline createDeadline(Timer.Sample authSample) {
        return Deadline.after(AUTHORISATION_DEADLINE, AUTHORISATION_FAILED_ANSWER, encodingFailureHandler, _ -> {
            authSample.stop(rejectedAuthTimer);
            alertService.raise(WARNING, AUTHORISATION_TIMED_OUT_ALERT_TITLE, "No token state within " + AUTHORISATION_DEADLINE);
        });
    }

    /// Answers the request, or dispatches it to its handler, by its token state. Runs on a container thread.
    private void actOnTokenState(String token, TokenState state, AsyncExchange exchange, Timer.Sample authSample) {
        // Empty once the deadline has answered the request.
        exchange.claim().ifPresent(claim -> actOnTokenState(token, state, exchange, claim, authSample));
    }

    private void actOnTokenState(String token, TokenState state, AsyncExchange exchange, AsyncExchange.Claim claim, Timer.Sample authSample) {
        try {
            switch (state) {
                case TokenAuthenticated tokenAuthenticated when !perUidRateLimiter.tryAdmit(tokenAuthenticated.profile().id()) -> {
                    // The user's identity is known only after verification, so this per-user limit runs here on the async body; the per-source guard runs
                    // up front, before the request goes async.
                    authSample.stop(rejectedAuthTimer);
                    claim.answer(PER_UID_RATE_LIMITED_ANSWER, encodingFailureHandler);
                }
                case TokenAuthenticated tokenAuthenticated -> {
                    authSample.stop(authenticatedAuthTimer);
                    ServletRequest request = claim.request();
                    tokenAuthenticated.customData()
                                      .ifPresent(data -> request.setAttribute(UserTokenAuthoriser.CUSTOM_DATA_REQUEST_ATTRIBUTE, data));
                    RequestContextFilter.setRequestContext(request, createRequestContext(token, tokenAuthenticated));
                    claim.dispatch();
                }
                case TokenNotAuthenticated tokenNotAuthenticated -> {
                    authSample.stop(rejectedAuthTimer);
                    Reason reason = tokenNotAuthenticated.reason();
                    Answer failureAnswer = createFailureAnswer(statusFor(reason), reason, tokenNotAuthenticated.technicalDescription());
                    claim.answer(failureAnswer, encodingFailureHandler);
                }
            }
        } catch (RuntimeException e) {
            // Nothing above this body turns a thrown exception into a response. The dispatch is the last step of its branch, so a throw always leaves the
            // response this claim's to answer.
            if (exchange.isOpen()) {
                alertService.raise(ERROR, AUTHORISATION_FAILED_ALERT_TITLE, logger, e);
            } else {
                logger.debug("Could not act on a token state, as the exchange has ended", e);
            }
            claim.answer(AUTHORISATION_FAILED_ANSWER, encodingFailureHandler);
        }
    }

    private UIRequestContext createRequestContext(String token, TokenAuthenticated tokenAuthenticated) {
        UIServerRuntime boundRuntime = tokenAuthenticated.uiServerRuntime();
        return new UIRequestContext(boundRuntime,
                                    onInvalidated -> userTokenAuthoriser.subscribeToTokenState(token, state -> {
                                        // Close the stream when the token is no longer authenticated, or when a later authenticated state carries a different
                                        // runtime than the one this request was bound to — the bound runtime is superseded, so the client must reconnect.
                                        switch (state) {
                                            case TokenAuthenticated authenticated when authenticated.uiServerRuntime() == boundRuntime -> {
                                            }
                                            case TokenAuthenticated _, TokenNotAuthenticated _ -> onInvalidated.run();
                                        }
                                    }));
    }

    private void failAuthorisation(AsyncExchange exchange, RuntimeException failure) {
        alertService.raise(ERROR, AUTHORISATION_FAILED_ALERT_TITLE, logger, failure);
        exchange.answer(AUTHORISATION_FAILED_ANSWER, encodingFailureHandler);
    }

    /// Creates the failure answer every rejection path shares, so its status, encoding and JSON keys have one owner.
    private static Answer createFailureAnswer(int status, Reason reason, String description) {
        return Answer.of(status, new JsonBody(FAILURE_WRITER, new AuthorisationFailure(reason.name(), description)));
    }

    private static int statusFor(Reason reason) {
        return switch (reason) {
            case INVALID -> UNAUTHORIZED_401;
            // 403, not 401: the bearer token is valid, so clients must not treat the refusal as an expired session.
            case USER_DISABLED, REGISTRATION_REFUSED -> FORBIDDEN_403;
            // 409, not 403: the request conflicts with an existing account rather than being forbidden, and the client renders its own remedy for it.
            case EMAIL_ALREADY_REGISTERED -> CONFLICT_409;
            case TECHNICAL_FAILURE -> SERVICE_UNAVAILABLE_503;
        };
    }

    private record AuthorisationFailure(String reason, String technicalDescription) {
        private AuthorisationFailure {
            checkNotNull(reason, "reason");
            checkNotNull(technicalDescription, "technicalDescription");
        }
    }

    /// Returns an admitted request's verification permit to [PreAuthAdmissionControl] exactly once. The token state's callback, a failure to deliver it and
    /// the end of the request's exchange each release it, on different threads, and a second release would return a permit the request never took, growing
    /// the pool without bound.
    private static final class SingleUseInFlightPermit {
        private final PreAuthAdmissionControl admissionControl;
        private final AtomicBoolean released = new AtomicBoolean();

        SingleUseInFlightPermit(PreAuthAdmissionControl admissionControl) {
            this.admissionControl = checkNotNull(admissionControl, "admissionControl");
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                admissionControl.releaseInFlight();
            }
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
