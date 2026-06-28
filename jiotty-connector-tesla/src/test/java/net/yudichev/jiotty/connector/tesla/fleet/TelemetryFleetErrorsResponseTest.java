package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Json;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryFleetErrorsResponseTest {
    @Test
    void parsesResponse() {
        @Language("JSON") var responseJson =
                """
                {
                  "response": {
                    "fleet_telemetry_errors": [
                      {
                        "created_at": "2025-12-23T23:27:53.832580642Z",
                        "error": "\\"webconnection error: dial tcp IP:443: connect: network is unreachable\\" cm_type=stream",
                        "error_name": "cloud_manager_error",
                        "hostname": "hostname",
                        "name": "theName",
                        "port": "443",
                        "txID": "d2bbc459-b758-4529-8ea2-1c3b2aaf2165",
                        "vin": "VIN"
                      },
                      {
                        "created_at": "2025-12-23T23:41:41.877688568Z",
                        "error": "\\"webconnection error: dial tcp IP:443: connect: network is unreachable\\" cm_type=stream",
                        "error_name": "cloud_manager_error",
                        "hostname": "hostname",
                        "name": "theName",
                        "port": "443",
                        "txID": "a8b18d75-b556-43a0-8a60-0937ff2873c3",
                        "vin": "VIN"
                      }
                    ]
                  }
                }
                """;
        assertThat(Json.parse(responseJson, new TypeToken<ResponseWrapper<TelemetryFleetErrorsResponse>>() {})).isEqualTo(
                ResponseWrapper.<TelemetryFleetErrorsResponse>builder()
                               .setResponse(TelemetryFleetErrorsResponse.of(
                                       List.of(
                                               TelemetryFleetError.builder()
                                                                  .setCreatedAt(Instant.parse("2025-12-23T23:27:53.832580642Z"))
                                                                  .setError("""
                                                                            "webconnection error: dial tcp IP:443: connect: network is unreachable" \
                                                                            cm_type=stream""")
                                                                  .setName("theName")
                                                                  .setVin("VIN")
                                                                  .build(),
                                               TelemetryFleetError.builder()
                                                                  .setCreatedAt(Instant.parse("2025-12-23T23:41:41.877688568Z"))
                                                                  .setError("""
                                                                            "webconnection error: dial tcp IP:443: connect: network is unreachable" \
                                                                            cm_type=stream""")
                                                                  .setName("theName")
                                                                  .setVin("VIN")
                                                                  .build()
                                       )))
                               .build());
    }
}