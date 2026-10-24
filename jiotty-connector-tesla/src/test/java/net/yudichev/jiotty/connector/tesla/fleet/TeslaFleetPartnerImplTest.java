package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.rest.OkHttpStubs.PendingCall;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.stubDeferredCalls;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.takeOnlyPendingCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class TeslaFleetPartnerImplTest {
    private static final String BASE_URL = "https://fleet.example.com/api/1";
    private static final String PARTNER_TOKEN = "partner-token-1";
    private static final String VIN = "5YJ3E1EA1JF000001";

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
    void deleteTelemetryConfig_deletesTheVehiclesConfigWithThePartnerToken() {
        CompletableFuture<TelemetryDeleteConfigResponse> result = partner.deleteTelemetryConfig(VIN);

        PendingCall call = takeOnlyPendingCall(pendingCalls);
        assertThat(call.request().method()).isEqualTo("DELETE");
        assertThat(call.request().url()).hasToString(BASE_URL + "/vehicles/" + VIN + "/fleet_telemetry_config");
        assertThat(call.request().header("Authorization")).isEqualTo("Bearer " + PARTNER_TOKEN);

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
