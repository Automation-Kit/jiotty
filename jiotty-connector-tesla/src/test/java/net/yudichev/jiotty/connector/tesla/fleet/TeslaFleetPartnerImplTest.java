package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.rest.HttpResponseException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.NOT_FOUND_404;
import static net.yudichev.jiotty.common.rest.HttpStatuses.OK_200;
import static net.yudichev.jiotty.common.rest.OkHttpStubs.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class TeslaFleetPartnerImplTest {
    private static final String BASE_URL = "https://fleet.example.com/api/1";
    private static final String PARTNER_TOKEN = "partner-token-1";
    private static final String VIN = "5YJ3E1EA1JF000001";

    private final List<PendingCall> pendingCalls = new ArrayList<>();
    @Mock
    private OkHttpClient httpClient;
    private TeslaFleetPartnerImpl partner;

    @BeforeEach
    void setUp() {
        lenient().when(httpClient.newCall(any())).thenAnswer(invocation -> {
            Request request = invocation.getArgument(0);
            Call call = mock(Call.class);
            lenient().when(call.request()).thenReturn(request);
            lenient().doAnswer(enqueueInvocation -> pendingCalls.add(new PendingCall(call, request, enqueueInvocation.getArgument(0))))
                     .when(call).enqueue(any());
            return call;
        });
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

        PendingCall call = singlePendingCall();
        assertThat(call.request().method()).isEqualTo("DELETE");
        assertThat(call.request().url()).hasToString(BASE_URL + "/vehicles/" + VIN + "/fleet_telemetry_config");
        assertThat(call.request().header("Authorization")).isEqualTo("Bearer " + PARTNER_TOKEN);

        respond(call, OK_200, "{\"response\":{\"updated_vehicles\":1}}");
        assertThat(result).succeedsWithin(Duration.ZERO).extracting(TelemetryDeleteConfigResponse::updatedVehicles).isEqualTo(1);
    }

    @Test
    void deleteTelemetryConfig_rejected_failsWithTheStatusAndNoVin() {
        CompletableFuture<TelemetryDeleteConfigResponse> result = partner.deleteTelemetryConfig(VIN);

        respond(singlePendingCall(), NOT_FOUND_404, "{\"error\":\"vehicle " + VIN + " not found\"}");

        assertThatThrownBy(result::join).rootCause()
                                        .isInstanceOfSatisfying(HttpResponseException.class,
                                                                e -> assertThat(e.statusCode()).isEqualTo(NOT_FOUND_404))
                                        .hasMessageNotContaining(VIN);
    }

    @Test
    void deleteTelemetryConfig_transportFailure_failsWithTheVinRedacted() {
        CompletableFuture<TelemetryDeleteConfigResponse> result = partner.deleteTelemetryConfig(VIN);

        PendingCall call = singlePendingCall();
        call.callback().onFailure(call.call(), new IOException("connection reset"));

        assertThatThrownBy(result::join).cause().hasMessageContaining("/vehicles/5YJ").hasMessageNotContaining(VIN);
    }

    private PendingCall singlePendingCall() {
        assertThat(pendingCalls).as("exactly one call should be in flight").hasSize(1);
        return pendingCalls.removeFirst();
    }

    private static void respond(PendingCall pendingCall, int status, String body) {
        asUnchecked(() -> pendingCall.callback().onResponse(pendingCall.call(), response(pendingCall.request(), status, body)));
    }

    private record PendingCall(Call call, Request request, Callback callback) {}
}
