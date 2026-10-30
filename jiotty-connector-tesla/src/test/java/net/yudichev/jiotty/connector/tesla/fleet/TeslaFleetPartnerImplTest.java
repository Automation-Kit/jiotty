package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.OkHttpStubs.PendingCall;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.rest.BearerTokens.BEARER_PREFIX;
import static net.yudichev.jiotty.common.rest.HttpStatuses.BAD_REQUEST_400;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.HttpStatuses.SERVICE_UNAVAILABLE_503;
import static net.yudichev.jiotty.common.rest.HttpStatuses.TOO_MANY_REQUESTS_429;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.bodyOf;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubDeferredCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.takeOnlyPendingCall;
import static net.yudichev.jiotty.connector.tesla.fleet.FleetCallAssertions.assertFailsWithStatus;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaFleetPartnerImpl.PARTNER_ACCOUNTS_PATH;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaFleetPartnerImpl.PARTNER_PUBLIC_KEY_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class TeslaFleetPartnerImplTest {
    private static final String BASE_URL = "https://fleet.example.com/api/1";
    private static final String PARTNER_TOKEN = "partner-token-1";
    private static final String VIN = "5YJ3E1EA1JF000001";
    private static final String DOMAIN = "joulary.example.com";

    @Mock
    private OkHttpClient httpClient;
    private List<PendingCall> pendingCalls;
    private TeslaFleetPartnerImpl partner;

    @BeforeEach
    void setUp() {
        pendingCalls = stubDeferredCalls(httpClient);
        partner = new TeslaFleetPartnerImpl("client-id", "client-secret", "scope", BASE_URL, Optional.empty()) {
            @Override
            OkHttpClient createHttpClient() {
                return httpClient;
            }

            @Override
            CompletableFuture<String> acquirePartnerToken() {
                return completedFuture(PARTNER_TOKEN);
            }
        };
        partner.start();
    }

    @AfterEach
    void tearDown() {
        if (partner != null) {
            partner.stop();
        }
    }

    @Test
    void registerPartnerDomain_postsTheDomainWithThePartnerTokenAndCompletesWithTheAccount() {
        CompletableFuture<PartnerAccount> result = partner.registerPartnerDomain(DOMAIN);

        PendingCall call = takeOnlyPendingCall(pendingCalls);
        assertThat(call.request().method()).isEqualTo("POST");
        assertThat(call.request().url()).hasToString(BASE_URL + PARTNER_ACCOUNTS_PATH);
        assertThat(call.request().header(AUTHORIZATION)).isEqualTo(BEARER_PREFIX + PARTNER_TOKEN);
        assertThat(bodyOf(call.request())).isEqualTo("{\"domain\": \"" + DOMAIN + "\"}");

        call.respond(OK_200, "{\"response\":{\"name\":\"Joulary\",\"domain\":\"" + DOMAIN + "\"}}");
        assertThat(result).succeedsWithin(Duration.ZERO).extracting(PartnerAccount::domain).isEqualTo(DOMAIN);
    }

    @Test
    void registerPartnerDomain_answeredWithAnErrorEnvelope_failsWithTeslasError() {
        CompletableFuture<PartnerAccount> result = partner.registerPartnerDomain(DOMAIN);

        takeOnlyPendingCall(pendingCalls).respond(BAD_REQUEST_400, "{\"error\":\"domain is not allowed\"}");

        assertThat(result).failsWithin(Duration.ZERO).withThrowableThat().withMessageContaining("domain is not allowed");
    }

    @ParameterizedTest
    @ValueSource(ints = {TOO_MANY_REQUESTS_429, SERVICE_UNAVAILABLE_503})
    void registerPartnerDomain_rateLimitedOrServerError_failsCarryingTheStatus(int status) {
        CompletableFuture<PartnerAccount> result = partner.registerPartnerDomain(DOMAIN);

        takeOnlyPendingCall(pendingCalls).respond(status, "{\"error\":\"try later\"}");

        assertFailsWithStatus(result, status);
    }

    @Test
    void getPartnerPublicKey_getsTheDomainsKeyWithThePartnerToken() {
        CompletableFuture<PartnerPublicKey> result = partner.getPartnerPublicKey(DOMAIN);

        PendingCall call = takeOnlyPendingCall(pendingCalls);
        assertThat(call.request().method()).isEqualTo("GET");
        assertThat(call.request().url()).hasToString(BASE_URL + PARTNER_PUBLIC_KEY_PATH + "?domain=" + DOMAIN);
        assertThat(call.request().header(AUTHORIZATION)).isEqualTo(BEARER_PREFIX + PARTNER_TOKEN);

        call.respond(OK_200, "{\"response\":{\"public_key\":\"04abcd\"}}");
        assertThat(result).succeedsWithin(Duration.ZERO).extracting(PartnerPublicKey::publicKey).isEqualTo("04abcd");
    }

    @Test
    void deleteTelemetryConfig_deletesTheVehiclesConfigWithThePartnerToken() {
        CompletableFuture<TelemetryDeleteConfigResponse> result = partner.deleteTelemetryConfig(VIN);

        PendingCall call = takeOnlyPendingCall(pendingCalls);
        assertThat(call.request().method()).isEqualTo("DELETE");
        assertThat(call.request().url()).hasToString(BASE_URL + "/vehicles/" + VIN + "/fleet_telemetry_config");
        assertThat(call.request().header(AUTHORIZATION)).isEqualTo(BEARER_PREFIX + PARTNER_TOKEN);

        call.respond(OK_200, "{\"response\":{\"updated_vehicles\":1}}");
        assertThat(result).succeedsWithin(Duration.ZERO).extracting(TelemetryDeleteConfigResponse::updatedVehicles).isEqualTo(1);
    }

    @Test
    void deleteTelemetryConfig_rejected_failsWithTheStatusAndNoVin() {
        CompletableFuture<TelemetryDeleteConfigResponse> result = partner.deleteTelemetryConfig(VIN);

        takeOnlyPendingCall(pendingCalls).respond(NOT_FOUND_404, "{\"error\":\"vehicle " + VIN + " not found\"}");

        assertThatThrownBy(result::join).rootCause()
                                        .isInstanceOfSatisfying(HttpResponseException.class,
                                                                e -> assertThat(e.statusCode()).isEqualTo(NOT_FOUND_404))
                                        .hasMessageNotContaining(VIN);
    }

    @Test
    void deleteTelemetryConfig_transportFailure_failsWithTheVinRedacted() {
        CompletableFuture<TelemetryDeleteConfigResponse> result = partner.deleteTelemetryConfig(VIN);

        takeOnlyPendingCall(pendingCalls).fail(new IOException("connection reset"));

        assertThatThrownBy(result::join).cause().hasMessageContaining("/vehicles/5YJ").hasMessageNotContaining(VIN);
    }
}
