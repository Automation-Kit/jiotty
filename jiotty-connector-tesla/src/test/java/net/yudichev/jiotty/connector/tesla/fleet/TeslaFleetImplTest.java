package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.async.ListenerBackedTaskExceptionHandlerRegistry;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.OkHttpStubs.PendingCall;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.connector.tesla.fleet.TeslaVehicle.CommandResult;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.REQUEST_TIMEOUT_408;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.bodyOf;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubDeferredCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.takeOnlyPendingCall;
import static net.yudichev.jiotty.connector.tesla.fleet.FleetCallAssertions.assertFailsWithStatus;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaVehicle.Endpoint.CHARGE_STATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/// Covers the credential-rejection hook every Fleet call goes through, and how Tesla's answers to a vehicle command map to a [CommandResult]. Vehicle calls
/// address a VIN-shaped [#VIN], so the PII log guard fails a test whose call logs or reports the VIN unredacted.
@ExtendWith(MockitoExtension.class)
class TeslaFleetImplTest {
    private static final String BASE_URL = "https://fleet.example.com/api/1";
    private static final String ACCESS_TOKEN = "access-token-1";
    private static final String VIN = "5YJ3E1EA1JF000001";
    private static final String ACCEPTED_BODY = "{\"response\":{\"result\":true,\"reason\":\"\"}}";
    private static final String OFFLINE_ERROR = "vehicle unavailable: vehicle is offline or asleep";
    private static final String OFFLINE_BODY = "{\"response\":null,\"error\":\"" + OFFLINE_ERROR + "\",\"error_description\":\"\"}";
    private static final String REFUSED_REASON = "car could not execute command: disconnected";
    private static final String REFUSED_BODY = "{\"response\":{\"result\":false,\"reason\":\"" + REFUSED_REASON + "\"}}";
    private static final String TOKEN_EXPIRED_BODY = "{\"error\":\"token expired\"}";
    /// An account with no vehicles on it. A populated list would put VINs in the response body, which [TeslaHttp#logResult] logs at DEBUG.
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
        teslaFleet = new TeslaFleetImpl(tokenManager, BASE_URL, Optional.empty(), new ListenerBackedTaskExceptionHandlerRegistry()) {
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

    @ParameterizedTest
    @MethodSource
    void command_postsToItsEndpointAndCompletesOnAcceptance(Function<TeslaVehicle, CompletableFuture<CommandResult>> command,
                                                            String expectedCommand,
                                                            String expectedBody) {
        CompletableFuture<CommandResult> result = command.apply(teslaFleet.vehicle(VIN));

        PendingCall call = takeOnlyPendingCall(pendingCalls);
        assertThat(call.request().method()).isEqualTo("POST");
        assertThat(call.request().url()).hasToString(BASE_URL + "/vehicles/" + VIN + "/command/" + expectedCommand);
        assertThat(bodyOf(call.request())).isEqualTo(expectedBody);

        call.respond(OK_200, ACCEPTED_BODY);
        assertThat(result).isCompletedWithValue(CommandResult.accepted());
    }

    static Stream<Arguments> command_postsToItsEndpointAndCompletesOnAcceptance() {
        return Stream.of(arguments(named("set charge limit", command(vehicle -> vehicle.setChargeLimit(80))), "set_charge_limit", "{\"percent\":80}"),
                         arguments(named("start charge", command(TeslaVehicle::startCharge)), "charge_start", ""),
                         arguments(named("stop charge", command(TeslaVehicle::stopCharge)), "charge_stop", ""),
                         arguments(named("start climate", command(TeslaVehicle::startAutoConditioning)), "auto_conditioning_start", ""),
                         arguments(named("stop climate", command(TeslaVehicle::stopAutoConditioning)), "auto_conditioning_stop", ""));
    }

    @ParameterizedTest
    @MethodSource
    void commandAnswer_completesWithTheMatchingResult(int status, String body, CommandResult expectedResult) {
        CompletableFuture<CommandResult> result = teslaFleet.vehicle(VIN).startCharge();
        respondWith(status, body);

        assertThat(result).isCompletedWithValue(expectedResult);
    }

    static Stream<Arguments> commandAnswer_completesWithTheMatchingResult() {
        return Stream.of(arguments(OK_200, ACCEPTED_BODY, CommandResult.accepted()),
                         arguments(OK_200, REFUSED_BODY, new CommandResult.Refused(REFUSED_REASON)),
                         arguments(REQUEST_TIMEOUT_408, OFFLINE_BODY, new CommandResult.Unavailable(OFFLINE_ERROR)),
                         arguments(BAD_REQUEST_400, REFUSED_BODY, new CommandResult.Refused(REFUSED_REASON)));
    }

    /// A rate limit (plain text or an error envelope), a server error and a client error with no envelope each fail with the HTTP status, which the
    /// caller's retry decision reads.
    @ParameterizedTest
    @MethodSource
    void unsuccessfulAnswerWithoutAUsableEnvelope_failsCarryingTheStatus(int status, String body) {
        CompletableFuture<CommandResult> result = teslaFleet.vehicle(VIN).startCharge();
        respondWith(status, body);

        assertFailsWithStatus(result, status);
    }

    static Stream<Arguments> unsuccessfulAnswerWithoutAUsableEnvelope_failsCarryingTheStatus() {
        return Stream.of(arguments(TOO_MANY_REQUESTS_429, "Retry in 47 seconds"),
                         arguments(TOO_MANY_REQUESTS_429, "{\"error\":\"Retry in 47 seconds\"}"),
                         arguments(INTERNAL_SERVER_ERROR_500, "Internal Server Error"),
                         arguments(SERVICE_UNAVAILABLE_503, "{}"),
                         arguments(SERVICE_UNAVAILABLE_503, OFFLINE_BODY),
                         arguments(NOT_FOUND_404, "Not Found"),
                         arguments(BAD_REQUEST_400, "{}"));
    }

    /// The failure goes on to logs and operator alerts, so the VIN in the request's URL is redacted where the message is built.
    @ParameterizedTest
    @MethodSource
    void callFailure_isReportedWithTheVinRedacted(Function<TeslaVehicle, CompletableFuture<?>> vehicleCall, String expectedMethod) {
        CompletableFuture<?> result = vehicleCall.apply(teslaFleet.vehicle(VIN));
        takeOnlyPendingCall(pendingCalls).fail(new IOException("connection reset"));

        assertThat(result).failsWithin(Duration.ZERO)
                          .withThrowableThat()
                          .withMessageContaining("call failed: " + expectedMethod)
                          .withMessageNotContaining(VIN);
    }

    static Stream<Arguments> callFailure_isReportedWithTheVinRedacted() {
        return Stream.of(arguments(named("command", (Function<TeslaVehicle, CompletableFuture<?>>) TeslaVehicle::startCharge), "POST"),
                         arguments(named("vehicle data", (Function<TeslaVehicle, CompletableFuture<?>>) vehicle -> vehicle.getData(Set.of(CHARGE_STATE))),
                                   "GET"),
                         arguments(named("telemetry config", (Function<TeslaVehicle, CompletableFuture<?>>) TeslaVehicle::telemetryGetConfig), "GET"));
    }

    @Test
    void telemetryConfigDeleted_completesWithTheUpdatedVehicleCount() {
        CompletableFuture<TelemetryDeleteConfigResponse> result = teslaFleet.vehicle(VIN).telemetryDeleteConfig();
        respondWith(OK_200, "{\"response\":{\"updated_vehicles\":1}}");

        assertThat(result).succeedsWithin(Duration.ZERO).satisfies(response -> assertThat(response.updatedVehicles()).isEqualTo(1));
    }

    @Test
    void acceptedCommandResult_readsAsAccepted() {
        assertThat(CommandResult.accepted()).hasToString("accepted");
    }

    @Test
    void wakeUpAnsweredWithAnErrorEnvelope_fails() {
        CompletableFuture<Void> result = teslaFleet.vehicle(VIN).wakeUp();
        respondWith(REQUEST_TIMEOUT_408, OFFLINE_BODY);

        assertThat(result).failsWithin(Duration.ZERO).withThrowableThat().withMessageContaining(OFFLINE_ERROR);
    }

    @Test
    void wakeUpAccepted_completes() {
        CompletableFuture<Void> result = teslaFleet.vehicle(VIN).wakeUp();
        respondWith(OK_200, "{\"response\":{\"state\":\"asleep\"}}");

        assertThat(result).isCompleted().isNotCompletedExceptionally();
    }

    private void respondWith(int status, String body) {
        takeOnlyPendingCall(pendingCalls).respond(status, body);
    }

    private static Function<TeslaVehicle, CompletableFuture<CommandResult>> command(Function<TeslaVehicle, CompletableFuture<CommandResult>> command) {
        return command;
    }
}
