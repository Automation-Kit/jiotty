package net.yudichev.jiotty.user.ui;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.user.persistence.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.user.ui.PreAuthAdmissionControl.Outcome.ADMITTED;
import static net.yudichev.jiotty.user.ui.PreAuthAdmissionControl.Outcome.VERIFY_SATURATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticatedUIRequestAuthoriserTest {
    private static final UserProfile PROFILE = new UserProfile("user-1",
                                                               Optional.of("user@example.com"),
                                                               Optional.of("Alex"),
                                                               ZoneId.of("Europe/London"),
                                                               Instant.parse("2026-03-13T12:00:00Z"),
                                                               Instant.parse("2026-03-13T12:00:00Z"));

    @Mock
    private UserTokenAuthoriser userTokenAuthoriser;
    private AuthenticatedUIRequestAuthoriser authoriser;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private AsyncContext asyncContext;
    @Mock
    private FilterChain chain;
    private StringWriter responseBody;
    private MeterRegistry meterRegistry;
    private ProgrammableClock clock;
    private PerUidRateLimiter perUidRateLimiter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        clock = new ProgrammableClock();
        clock.setTime(Instant.parse("2026-07-20T12:00:00Z"));
        // Generous limits so the existing cases exercise the authenticated path; the admission cases below tighten them.
        perUidRateLimiter = perUidLimiterWith(1000.0);
        var admissionControl = new PreAuthAdmissionControl(clock, 1000.0, 1000, false, meterRegistry);
        authoriser = new AuthenticatedUIRequestAuthoriser(userTokenAuthoriser, admissionControl, perUidRateLimiter, meterRegistry);
    }

    /// A source past its rate is refused before the request goes async, so it never reaches token verification.
    @Test
    void rejectsWithTooManyRequestsOncePastTheRate() throws Exception {
        PreAuthAdmissionControl control = admissionControlWith(1.0, 10);
        prepareResponseBody();
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        control.tryAdmit(request);   // spends this source's allowance

        authoriser.authorise(request, response, chain);

        verify(response).setStatus(429);
        verify(request, never()).startAsync();
        verify(userTokenAuthoriser, never()).deliverTokenStateTo(anyString(), any());
        assertThat(responseBody.toString()).contains("Too many authentication attempts");
    }

    /// With every verification slot taken, a further caller is shed rather than queued behind them.
    @Test
    void rejectsWithServiceUnavailableWhenVerificationSlotsAreExhausted() throws Exception {
        PreAuthAdmissionControl control = admissionControlWith(1000.0, 1);
        prepareResponseBody();
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        control.tryAdmit(request);   // takes the only verification slot

        authoriser.authorise(request, response, chain);

        verify(response).setStatus(503);
        verify(request, never()).startAsync();
        verify(userTokenAuthoriser, never()).deliverTokenStateTo(anyString(), any());
    }

    /// An authenticated user past its per-user API rate is shed with 429 after verification, before its request is dispatched onward — and its verification
    /// slot still returns. The user's identity is known only after verification, so this limit runs on the async body, unlike the per-source one up front.
    @Test
    void rejectsWithTooManyRequestsWhenTheUserIsPastItsApiRate(@Mock HttpServletRequest laterRequest) throws Exception {
        var perUid = perUidLimiterWith(1.0);
        var control = new PreAuthAdmissionControl(clock, 1000.0, 1, false, meterRegistry);
        authoriser = new AuthenticatedUIRequestAuthoriser(userTokenAuthoriser, control, perUid, meterRegistry);
        perUid.tryAdmit(PROFILE.id());   // spends this user's allowance
        arrangeAdmittedRequestAwaitingVerification(laterRequest);
        prepareAsyncContextStart();
        prepareResponseBody();
        deliverTokenState(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, new FakeUIServer(), Optional.empty()));

        authoriser.authorise(request, response, chain);

        verify(response).setStatus(429);
        verify(asyncContext).complete();
        verify(asyncContext, never()).dispatch();
        assertThat(responseBody.toString()).contains("Too many requests");
        assertThat(control.tryAdmit(laterRequest)).as("the verification slot returns even though the user was rate-limited").isEqualTo(ADMITTED);
    }

    /// However the async cycle ends, the admitted request's verification slot goes back to the pool. The cycle has its timeout disabled, so a slot that
    /// leaked on any of these paths would be lost for the life of the process and permanently shrink the pool.
    @ParameterizedTest
    @MethodSource
    void returnsTheVerificationSlotWhenTheAsyncCycleEnds(String endingName,
                                                         Consumer<AsyncListener> ending,
                                                         @Mock HttpServletRequest laterRequest,
                                                         @Captor ArgumentCaptor<AsyncListener> listenerCaptor) throws Exception {
        PreAuthAdmissionControl control = admissionControlWith(1000.0, 1);
        arrangeAdmittedRequestAwaitingVerification(laterRequest);

        authoriser.authorise(request, response, chain);   // admitted, holding the only slot

        verify(asyncContext).addListener(listenerCaptor.capture());
        assertThat(control.tryAdmit(laterRequest)).as("%s: slot still held while verification is outstanding", endingName).isEqualTo(VERIFY_SATURATED);

        ending.accept(listenerCaptor.getValue());

        assertThat(control.tryAdmit(laterRequest)).as("%s: slot returned", endingName).isEqualTo(ADMITTED);
    }

    static Stream<Arguments> returnsTheVerificationSlotWhenTheAsyncCycleEnds() {
        return Stream.of(arguments("onComplete", (Consumer<AsyncListener>) listener -> asUnchecked(() -> listener.onComplete(null))),
                         arguments("onTimeout", (Consumer<AsyncListener>) listener -> asUnchecked(() -> listener.onTimeout(null))),
                         arguments("onError", (Consumer<AsyncListener>) listener -> asUnchecked(() -> listener.onError(null))),
                         // A downstream handler re-entering async discards this listener, so the slot has to come back here or never.
                         arguments("onStartAsync", (Consumer<AsyncListener>) listener -> asUnchecked(() -> listener.onStartAsync(null))));
    }

    /// The servlet container may run several completion callbacks for one async cycle — Jetty follows `onError` with `onComplete`. Releasing per callback
    /// would hand back permits the cycle never took, growing the pool past its bound and driving the occupancy gauge negative.
    @Test
    void returnsTheVerificationSlotAtMostOnce(@Mock HttpServletRequest laterRequest,
                                              @Captor ArgumentCaptor<AsyncListener> listenerCaptor) throws Exception {
        // Its own registry: a gauge name registered by the fixture's control would otherwise win, and this case asserts on occupancy.
        var ownRegistry = new SimpleMeterRegistry();
        var control = new PreAuthAdmissionControl(clock, 1000.0, 1, false, ownRegistry);
        authoriser = new AuthenticatedUIRequestAuthoriser(userTokenAuthoriser, control, perUidRateLimiter, ownRegistry);
        arrangeAdmittedRequestAwaitingVerification(laterRequest);

        authoriser.authorise(request, response, chain);

        verify(asyncContext).addListener(listenerCaptor.capture());
        AsyncListener listener = listenerCaptor.getValue();
        listener.onError(null);
        listener.onComplete(null);

        assertThat(control.tryAdmit(laterRequest)).as("the one returned slot is reusable").isEqualTo(ADMITTED);
        assertThat(control.tryAdmit(laterRequest)).as("but only one slot ever existed").isEqualTo(VERIFY_SATURATED);
        assertThat(ownRegistry.get("preauth_verify_inflight").gauge().value())
                .as("occupancy stays at the one live admission rather than going negative")
                .isEqualTo(1.0);
    }

    /// The slot bounds verification, not the async cycle: a handler may hold that cycle open for hours (an SSE stream does), and holding its slot for that
    /// whole time would exhaust the pool after a handful of streams.
    @Test
    void returnsTheVerificationSlotOnceTheTokenStateIsActedOn(@Mock HttpServletRequest laterRequest) throws Exception {
        PreAuthAdmissionControl control = admissionControlWith(1000.0, 1);
        arrangeAdmittedRequestAwaitingVerification(laterRequest);
        prepareAsyncContextStart();
        deliverTokenState(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, new FakeUIServer(), Optional.empty()));

        authoriser.authorise(request, response, chain);

        verify(asyncContext).dispatch();
        assertThat(control.tryAdmit(laterRequest)).as("the slot is free once the token has been verified and dispatched onward").isEqualTo(ADMITTED);
    }

    /// A client that disconnects mid-verification makes the failure write throw. Without a terminal guard the async cycle would stay open for the life of the
    /// process with its slot held.
    @Test
    void returnsTheVerificationSlotWhenTheAuthorisationBodyThrows(@Mock HttpServletRequest laterRequest) throws Exception {
        PreAuthAdmissionControl control = admissionControlWith(1000.0, 1);
        arrangeAdmittedRequestAwaitingVerification(laterRequest);
        prepareAsyncContextStart();
        when(response.getWriter()).thenThrow(new IOException("client gone"));
        deliverTokenState(new UserTokenAuthoriser.TokenNotAuthenticated(UserTokenAuthoriser.TokenNotAuthenticated.Reason.INVALID, "expired"));

        authoriser.authorise(request, response, chain);

        verify(asyncContext).complete();
        assertThat(control.tryAdmit(laterRequest)).as("the slot returns even though writing the failure blew up").isEqualTo(ADMITTED);
    }

    /// An [Error] means the JVM is in no state to serve this request, so it propagates untouched rather than being reported as a failed authorisation — but
    /// the slot still comes back on the way out.
    @Test
    void propagatesAnErrorWhileStillReturningTheVerificationSlot(@Mock HttpServletRequest laterRequest) throws Exception {
        PreAuthAdmissionControl control = admissionControlWith(1000.0, 1);
        arrangeAdmittedRequestAwaitingVerification(laterRequest);
        prepareAsyncContextStart();
        var fatal = new StackOverflowError("stack exhausted");
        when(response.getWriter()).thenThrow(fatal);
        deliverTokenState(new UserTokenAuthoriser.TokenNotAuthenticated(UserTokenAuthoriser.TokenNotAuthenticated.Reason.INVALID, "expired"));

        assertThatThrownBy(() -> authoriser.authorise(request, response, chain)).isSameAs(fatal);

        verify(asyncContext, never()).complete();
        assertThat(control.tryAdmit(laterRequest)).as("the slot returns even though the error was left to propagate").isEqualTo(ADMITTED);
    }

    /// Once the response is committed its status and headers are already on the wire, so the failure report is skipped and only the async cycle is closed out.
    @Test
    void closesTheAsyncCycleWithoutReportingWhenTheResponseIsAlreadyCommitted(@Mock HttpServletRequest laterRequest) throws Exception {
        PreAuthAdmissionControl control = admissionControlWith(1000.0, 1);
        arrangeAdmittedRequestAwaitingVerification(laterRequest);
        prepareAsyncContextStart();
        when(response.getWriter()).thenThrow(new IOException("client gone"));
        when(response.isCommitted()).thenReturn(true);
        deliverTokenState(new UserTokenAuthoriser.TokenNotAuthenticated(UserTokenAuthoriser.TokenNotAuthenticated.Reason.INVALID, "expired"));

        authoriser.authorise(request, response, chain);

        verify(asyncContext).complete();
        verify(response).setStatus(401);   // the original failure report, which then blew up writing its body
        verify(response, never()).setStatus(503);
        assertThat(control.tryAdmit(laterRequest)).isEqualTo(ADMITTED);
    }

    /// Anything that is not a non-blank `Bearer <token>` is treated as no token at all, so it is refused before any verification work.
    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwYXNz", "Bearer", "Bearer    ", "bearer token-1"})
    @NullSource
    void rejectsMissingBearerToken(String authorizationHeader) throws Exception {
        prepareResponseBody();
        when(request.getHeader("Authorization")).thenReturn(authorizationHeader);

        authoriser.authorise(request, response, chain);

        verify(response).setStatus(401);
        verify(userTokenAuthoriser, never()).deliverTokenStateTo(anyString(), any());
        assertThat(responseBody.toString()).contains("INVALID").contains("Missing or invalid Authorization bearer token");
        assertThat(meterRegistry.find("ui_authorise_seconds").tag("outcome", "missing_token").timer())
                .as("ui_authorise_seconds is tagged outcome=missing_token when no bearer token is present")
                .isNotNull()
                .returns(1L, Timer::count);
    }

    @ParameterizedTest
    @EnumSource(UserTokenAuthoriser.TokenNotAuthenticated.Reason.class)
    void mapsNotAuthenticatedStatesToHttpStatuses(UserTokenAuthoriser.TokenNotAuthenticated.Reason reason) throws Exception {
        prepareAsyncContext();
        prepareResponseBody();
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        doAnswer(invocation -> {
            Consumer<? super UserTokenAuthoriser.TokenState> handler = invocation.getArgument(1);
            handler.accept(new UserTokenAuthoriser.TokenNotAuthenticated(reason, "failure"));
            return null;
        }).when(userTokenAuthoriser).deliverTokenStateTo(eq("token-1"), any());

        authoriser.authorise(request, response, chain);

        verify(asyncContext).setTimeout(0);
        verify(asyncContext).complete();
        verify(response).setStatus(expectedStatus(reason));
        assertThat(responseBody.toString()).contains(reason.name()).contains("failure");
        assertThat(meterRegistry.find("ui_authorise_seconds").tag("outcome", "rejected").timer())
                .as("ui_authorise_seconds is tagged outcome=rejected when the token is not authenticated")
                .isNotNull()
                .returns(1L, Timer::count);
    }

    @Test
    void createsRequestContextAndProvidesInvalidationSubscription() throws Exception {
        prepareAsyncContext();
        prepareRequestContextAttributes();
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        var uiServer = new FakeUIServer();
        var tokenStateSubscriptionHandler = new MutableReference<Consumer<? super UserTokenAuthoriser.TokenState>>();
        doAnswer(invocation -> {
            Consumer<? super UserTokenAuthoriser.TokenState> handler = invocation.getArgument(1);
            handler.accept(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, uiServer, Optional.empty()));
            return null;
        }).when(userTokenAuthoriser).deliverTokenStateTo(eq("token-1"), any());
        when(userTokenAuthoriser.subscribeToTokenState(eq("token-1"), any())).thenAnswer(invocation -> {
            tokenStateSubscriptionHandler.set(invocation.getArgument(1));
            return Closeable.noop();
        });

        authoriser.authorise(request, response, chain);

        verify(asyncContext).dispatch();
        assertThat(RequestContextFilter.requestContext(request).uiServerRuntime()).isSameAs(uiServer);
        assertThat(meterRegistry.find("ui_authorise_seconds").tag("outcome", "authenticated").timer())
                .as("ui_authorise_seconds is tagged outcome=authenticated on the success path")
                .isNotNull()
                .returns(1L, Timer::count);

        var invalidated = new MutableReference<>(false);
        Closeable subscription = RequestContextFilter.requestContext(request).subscribeToInvalidation(() -> invalidated.set(true));
        tokenStateSubscriptionHandler.get().accept(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, uiServer, Optional.empty()));
        assertThat(invalidated.get()).isFalse();

        tokenStateSubscriptionHandler.get()
                                     .accept(new UserTokenAuthoriser.TokenNotAuthenticated(UserTokenAuthoriser.TokenNotAuthenticated.Reason.INVALID,
                                                                                           "expired"));
        assertThat(invalidated.get()).isTrue();
        subscription.close();
    }

    @Test
    void invalidatesStreamWhenAuthenticatedStateCarriesDifferentRuntime() throws Exception {
        prepareAsyncContext();
        prepareRequestContextAttributes();
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        var boundRuntime = new FakeUIServer();
        var tokenStateSubscriptionHandler = new MutableReference<Consumer<? super UserTokenAuthoriser.TokenState>>();
        doAnswer(invocation -> {
            Consumer<? super UserTokenAuthoriser.TokenState> handler = invocation.getArgument(1);
            handler.accept(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, boundRuntime, Optional.empty()));
            return null;
        }).when(userTokenAuthoriser).deliverTokenStateTo(eq("token-1"), any());
        when(userTokenAuthoriser.subscribeToTokenState(eq("token-1"), any())).thenAnswer(invocation -> {
            tokenStateSubscriptionHandler.set(invocation.getArgument(1));
            return Closeable.noop();
        });

        authoriser.authorise(request, response, chain);

        var invalidated = new MutableReference<>(false);
        Closeable subscription = RequestContextFilter.requestContext(request).subscribeToInvalidation(() -> invalidated.set(true));

        // a later authenticated state carrying a DIFFERENT runtime supersedes the bound one, so the stream must close
        tokenStateSubscriptionHandler.get().accept(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, new FakeUIServer(), Optional.empty()));
        assertThat(invalidated.get()).isTrue();
        subscription.close();
    }

    @Test
    void exposesTokenCustomDataAsRequestAttribute() throws Exception {
        prepareAsyncContext();
        prepareRequestContextAttributes();
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        var customData = new Object();
        doAnswer(invocation -> {
            Consumer<? super UserTokenAuthoriser.TokenState> handler = invocation.getArgument(1);
            handler.accept(new UserTokenAuthoriser.TokenAuthenticated(PROFILE, new FakeUIServer(), Optional.of(customData)));
            return null;
        }).when(userTokenAuthoriser).deliverTokenStateTo(eq("token-1"), any());

        authoriser.authorise(request, response, chain);

        verify(asyncContext).dispatch();
        assertThat(request.getAttribute(UserTokenAuthoriser.CUSTOM_DATA_REQUEST_ATTRIBUTE)).isSameAs(customData);
    }

    /// Builds a control with the given limits and rewires the authoriser around it, which every admission case needs before it can arrange anything else.
    private PreAuthAdmissionControl admissionControlWith(double requestsPerSecond, int maxInFlightVerifications) {
        var control = new PreAuthAdmissionControl(clock, requestsPerSecond, maxInFlightVerifications, false, meterRegistry);
        authoriser = new AuthenticatedUIRequestAuthoriser(userTokenAuthoriser, control, perUidRateLimiter, meterRegistry);
        return control;
    }

    private PerUidRateLimiter perUidLimiterWith(double requestsPerSecond) {
        // Burst equal to the rate keeps these cases simple: spending the rate's worth exhausts the allowance.
        return new PerUidRateLimiter(clock, requestsPerSecond, requestsPerSecond, meterRegistry);
    }

    /// Arranges a request that admission lets through and that then sits awaiting its token state, plus a second source for probing the pool.
    private void arrangeAdmittedRequestAwaitingVerification(HttpServletRequest laterRequest) {
        when(request.startAsync()).thenReturn(asyncContext);
        when(request.getHeader("Authorization")).thenReturn("Bearer token-1");
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(laterRequest.getRemoteAddr()).thenReturn("10.0.0.2");
    }

    /// Runs whatever the authoriser submits to the async context inline, so the verification body executes within the test call.
    private void prepareAsyncContextStart() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(asyncContext).start(any(Runnable.class));
    }

    private void deliverTokenState(UserTokenAuthoriser.TokenState state) {
        doAnswer(invocation -> {
            Consumer<? super UserTokenAuthoriser.TokenState> handler = invocation.getArgument(1);
            handler.accept(state);
            return null;
        }).when(userTokenAuthoriser).deliverTokenStateTo(eq("token-1"), any());
    }

    private void prepareAsyncContext() {
        when(request.startAsync()).thenReturn(asyncContext);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(asyncContext).start(any(Runnable.class));
    }

    private void prepareResponseBody() throws Exception {
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    private void prepareRequestContextAttributes() {
        var requestAttributes = new HashMap<String, Object>();
        doAnswer(invocation -> {
            requestAttributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString())).thenAnswer(invocation -> {
            String attrName = invocation.getArgument(0);
            return requestAttributes.get(attrName);
        });
    }

    private static int expectedStatus(UserTokenAuthoriser.TokenNotAuthenticated.Reason reason) {
        return switch (reason) {
            case INVALID -> 401;
            case USER_DISABLED, REGISTRATION_REFUSED -> 403;
            case TECHNICAL_FAILURE -> 503;
        };
    }
}
