package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.BindingAnnotation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.adminalerts.AdminAlertService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.adminalerts.AdminAlertSeverity.ERROR;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.CONFLICT_409;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenAuthenticated;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenNotAuthenticated;
import static net.yudichev.jiotty.user.ui.UserTokenAuthoriser.TokenNotAuthenticated.Reason;

final class AuthenticatedUIRequestAuthoriser implements UIRequestAuthoriser {
    private static final Logger logger = LogManager.getLogger(AuthenticatedUIRequestAuthoriser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper(new JsonFactory());
    private static final String AUTHORISE_TIMER = "ui_authorise_seconds";

    private final UserTokenAuthoriser userTokenAuthoriser;
    private final PreAuthAdmissionControl admissionControl;
    private final PerUidRateLimiter perUidRateLimiter;
    private final AdminAlertService alertService;
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
        this.meterRegistry = checkNotNull(meterRegistry, "meterRegistry");
        missingTokenAuthTimer = meterRegistry.timer(AUTHORISE_TIMER, "outcome", "missing_token");
        authenticatedAuthTimer = meterRegistry.timer(AUTHORISE_TIMER, "outcome", "authenticated");
        rejectedAuthTimer = meterRegistry.timer(AUTHORISE_TIMER, "outcome", "rejected");
    }

    @Override
    public void authorise(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException {
        Timer.Sample authSample = Timer.start(meterRegistry);
        String token = bearerToken(request);
        if (token == null) {
            authSample.stop(missingTokenAuthTimer);
            writeAuthenticationFailure(response,
                                       new TokenNotAuthenticated(Reason.INVALID, "Missing or invalid Authorization bearer token"));
            return;
        }

        // Admission is decided before the request goes async, so a rejected caller costs one header parse.
        switch (admissionControl.tryAdmit(request)) {
            case RATE_LIMITED -> {
                authSample.stop(rejectedAuthTimer);
                writeAdmissionRejection(response, TOO_MANY_REQUESTS_429, "Too many authentication attempts");
                return;
            }
            case VERIFY_SATURATED -> {
                authSample.stop(rejectedAuthTimer);
                writeAdmissionRejection(response, SERVICE_UNAVAILABLE_503, "Authentication temporarily unavailable");
                return;
            }
            case ADMITTED -> {
            }
        }

        AsyncContext asyncContext = request.startAsync();
        asyncContext.setTimeout(0);
        // The permit bounds concurrent *verifications*, so it is returned as soon as the token state has been acted on. A downstream handler may keep the
        // async cycle open for hours (an SSE stream does), which would tie up a verification slot for that whole time. The `finally` covers every exit,
        // including a throwable that propagates out of this body. The listener is a backstop for the paths that never reach the callback at all, and
        // releases at onStartAsync too because a re-dispatch discards this listener (ServletRequest#startAsync clears the list after notifying it).
        var permit = new SingleUseInFlightPermit(admissionControl);
        asyncContext.addListener(permit.asBackstopListener());
        userTokenAuthoriser.deliverTokenStateTo(token, state -> asyncContext.start(() -> {
            try {
                switch (state) {
                    case TokenAuthenticated tokenAuthenticated when !perUidRateLimiter.tryAdmit(tokenAuthenticated.profile().id()) -> {
                        // The user's identity is known only after verification, so this per-user limit runs here on the async body; the per-source guard runs
                        // up front, before the request goes async.
                        authSample.stop(rejectedAuthTimer);
                        asUnchecked(() -> writeAdmissionRejection(response, TOO_MANY_REQUESTS_429, "Too many requests"));
                        asyncContext.complete();
                    }
                    case TokenAuthenticated tokenAuthenticated -> {
                        authSample.stop(authenticatedAuthTimer);
                        tokenAuthenticated.customData()
                                          .ifPresent(data -> request.setAttribute(UserTokenAuthoriser.CUSTOM_DATA_REQUEST_ATTRIBUTE, data));
                        RequestContextFilter.setRequestContext(request, createRequestContext(token, tokenAuthenticated));
                        asyncContext.dispatch();
                    }
                    case TokenNotAuthenticated tokenNotAuthenticated -> {
                        authSample.stop(rejectedAuthTimer);
                        asUnchecked(() -> writeAuthenticationFailure(response, tokenNotAuthenticated));
                        asyncContext.complete();
                    }
                }
            } catch (RuntimeException e) {
                // Unlike a synchronous service() call, nothing above this body turns a thrown exception into a response: it would hang the request until the
                // process dies. A client disconnecting mid-verification makes the write throw, so this path is a live one.
                alertService.raise(ERROR, "Failed to complete authorisation of a request", logger, e);
                completeExceptionally(asyncContext, response);
            } finally {
                permit.release();
            }
        }));
    }

    /// Completes the async cycle after the authorisation body failed, reporting the failure to the caller when the response is still uncommitted.
    private static void completeExceptionally(AsyncContext asyncContext, HttpServletResponse response) {
        try {
            if (!response.isCommitted()) {
                writeAuthenticationFailure(response, new TokenNotAuthenticated(Reason.TECHNICAL_FAILURE, "Authorisation failed"));
            }
        } catch (IOException | RuntimeException e) {
            logger.warn("Unable to report an authorisation failure to the caller", e);
        } finally {
            asyncContext.complete();
        }
    }

    private static void writeAdmissionRejection(HttpServletResponse response, int status, String description) throws IOException {
        writeJsonFailure(response, status, Reason.TECHNICAL_FAILURE, description);
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

    private static @Nullable String bearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null) {
            return null;
        }
        if (!authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        return token.isBlank() ? null : token;
    }

    private static void writeAuthenticationFailure(HttpServletResponse response, TokenNotAuthenticated state) throws IOException {
        writeJsonFailure(response, statusFor(state.reason()), state.reason(), state.technicalDescription());
    }

    /// Writes the failure envelope every rejection path shares, so its status, encoding and JSON keys have one owner.
    private static void writeJsonFailure(HttpServletResponse response, int status, Reason reason, String description) throws IOException {
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        response.setStatus(status);
        MAPPER.writeValue(response.getWriter(), Map.of("reason", reason.name(), "technicalDescription", description));
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

    /// Returns an admitted request's verification permit to [PreAuthAdmissionControl] exactly once, however many of the completion paths run. The container
    /// container can invoke several of them for one async cycle — Jetty follows [AsyncListener#onError] with [AsyncListener#onComplete] — and an unguarded
    /// release would hand back a permit the cycle never took, growing the pool without bound.
    private static final class SingleUseInFlightPermit {
        private final PreAuthAdmissionControl admissionControl;
        private final AtomicBoolean released = new AtomicBoolean();

        SingleUseInFlightPermit(PreAuthAdmissionControl admissionControl) {
            this.admissionControl = admissionControl;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                admissionControl.releaseInFlight();
            }
        }

        AsyncListener asBackstopListener() {
            return new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    release();
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    release();
                }

                @Override
                public void onError(AsyncEvent event) {
                    release();
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    release();
                }
            };
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
