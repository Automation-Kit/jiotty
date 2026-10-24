package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.OkHttpStubs.PendingCall;
import net.yudichev.jiotty.common.rest.RestClients;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubDeferredCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.takeOnlyPendingCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/// Covers the credential-rejection hook every Fleet call goes through: a 401 says the token that request carried is no longer accepted, so it is invalidated;
/// any other outcome leaves it alone. Only account-level endpoints are exercised, because [RestClients] logs the request URL verbatim and a per-vehicle URL
/// would put a VIN in the log, tripping the PII guard on a leak that predates this class.
@ExtendWith(MockitoExtension.class)
class TeslaFleetImplTest {
    private static final String ACCESS_TOKEN = "access-token-1";
    private static final String VIN = "5YJ3E1EA1JF000001";
    private static final String TOKEN_EXPIRED_BODY = "{\"error\":\"token expired\"}";
    /// An account with no vehicles on it. A populated list would put VINs in the response body, which [RestClients] logs verbatim — the same pre-existing
    /// leak the class doc notes for request URLs.
    private static final String LIST_VEHICLES_BODY = "{\"response\":[]}";
    @Mock
    private OAuth2TokenManager tokenManager;
    @Mock
    private OkHttpClient httpClient;
    private List<PendingCall> pendingCalls;
    private Consumer<AuthState> authStateHandler;
    private TeslaFleetImpl teslaFleet;

    @BeforeEach
    void setUp() {
        lenient().when(tokenManager.subscribeToAccessTokenState(any())).thenAnswer(invocation -> {
            authStateHandler = invocation.getArgument(0);
            authStateHandler.accept(new AuthState.Success(ACCESS_TOKEN));
            return Closeable.noop();
        });
        pendingCalls = stubDeferredCalls(httpClient);
        teslaFleet = new TeslaFleetImpl(tokenManager, "https://fleet.example.com/api/1", Optional.empty(), new ListenerBackedTaskExceptionHandlerRegistry()) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }
        };
        teslaFleet.start();
    }

    @AfterEach
    void tearDown() {
        if (teslaFleet != null) {
            teslaFleet.stop();
        }
    }

    @Test
    void unauthorisedOnGet_invalidatesTheTokenTheRequestCarried() {
        CompletableFuture<?> result = teslaFleet.listVehicles();
        respondWith(UNAUTHORIZED_401, TOKEN_EXPIRED_BODY);

        assertThat(result).isCompletedExceptionally();
        verify(tokenManager).invalidate(eq(ACCESS_TOKEN), contains("rejected the credential"));
    }

    /// The POST paths ask for the error body to be parsed, because Tesla reports domain refusals in a `{"error": …}` envelope. A 401 is not one of those, and
    /// must still surface as a status the hook can classify.
    @Test
    void unauthorisedOnPost_invalidatesTheTokenDespiteTheErrorEnvelope() {
        CompletableFuture<?> result = teslaFleet.telemetryCreateConfig(
                TelemetryCreateConfigRequest.builder()
                                            .setVins(List.of(VIN))
                                            .setConfig(TelemetryConfig.builder()
                                                                      .setHostname("telemetry.example.com")
                                                                      .setPort(443)
                                                                      .setCaCertificate("ca-cert")
                                                                      .build())
                                            .build());
        respondWith(UNAUTHORIZED_401, TOKEN_EXPIRED_BODY);

        assertThat(result).isCompletedExceptionally();
        verify(tokenManager).invalidate(eq(ACCESS_TOKEN), contains("rejected the credential"));
    }

    @Test
    void serverError_leavesTheCredentialAlone() {
        CompletableFuture<?> result = teslaFleet.listVehicles();
        respondWith(INTERNAL_SERVER_ERROR_500, "Internal Server Error");

        assertThat(result).isCompletedExceptionally();
        verify(tokenManager, never()).invalidate(any(), any());
    }

    @Test
    void success_leavesTheCredentialAlone() {
        CompletableFuture<?> result = teslaFleet.listVehicles();
        respondWith(OK_200, LIST_VEHICLES_BODY);

        assertThat(result).isCompleted();
        verify(tokenManager, never()).invalidate(any(), any());
    }

    /// A retrying caller cancels the future it was handed once it gives up. The call is already in flight, so its verdict still has to be acted on — which it
    /// is only because the hook rides the call's own future rather than a stage derived from it.
    @Test
    void rejectionArrivingAfterTheCallerCancelled_stillInvalidates() {
        CompletableFuture<?> result = teslaFleet.listVehicles();
        result.cancel(true);

        respondWith(UNAUTHORIZED_401, TOKEN_EXPIRED_BODY);

        verify(tokenManager).invalidate(eq(ACCESS_TOKEN), contains("rejected the credential"));
    }

    /// A response landing after teardown must not reach the token manager: it is stopped by then, and asking it to invalidate would throw.
    @Test
    void rejectionArrivingAfterTeardown_doesNotInvalidate() {
        teslaFleet.listVehicles();
        teslaFleet.stop();

        respondWith(UNAUTHORIZED_401, TOKEN_EXPIRED_BODY);

        verify(tokenManager, never()).invalidate(any(), any());
    }

    /// A completion callback discards whatever escapes it, so an invalidation that throws is caught and logged here. The caller still sees the rejection that
    /// prompted it.
    @Test
    void invalidationThrowing_leavesTheCallerTheRejection() {
        doThrow(new IllegalStateException("token manager is stopped")).when(tokenManager).invalidate(any(), any());

        CompletableFuture<?> result = teslaFleet.listVehicles();
        respondWith(UNAUTHORIZED_401, TOKEN_EXPIRED_BODY);

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::join).hasRootCauseInstanceOf(HttpResponseException.class);
        verify(tokenManager).invalidate(eq(ACCESS_TOKEN), contains("rejected the credential"));
    }

    /// With no usable token there is nothing to reject, so the call fails without reaching the network and without a rejection to report.
    @Test
    void noValidToken_failsWithoutCallingOrInvalidating() {
        authStateHandler.accept(new AuthState.PermanentFailure("not authenticated"));

        assertThat(teslaFleet.listVehicles()).isCompletedExceptionally();
        assertThat(pendingCalls).isEmpty();
        verify(tokenManager, never()).invalidate(any(), any());
    }

    private void respondWith(int status, String body) {
        takeOnlyPendingCall(pendingCalls).respond(status, body);
    }
}
