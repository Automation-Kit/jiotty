package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.connector.mqtt.InMemoryMqtt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryConnectivityStatus.CONNECTED;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TBatteryLevel;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TBatteryLevelValue;
import static org.assertj.core.api.Assertions.assertThat;

class MqttTeslaTelemetryTest {
    private static final String VIN = "5YJ3E1EA1JF000001";
    private static final String REDACTED_VIN = "5YJ…";
    private static final String TOPIC_BASE = "telemetry";

    private final List<TelemetryResult<TelemetryField>> metricResults = new ArrayList<>();
    private final List<TelemetryResult<TelemetryConnectivityEvent>> connectivityResults = new ArrayList<>();
    private InMemoryMqtt mqtt;

    @BeforeEach
    void setUp() {
        mqtt = new InMemoryMqtt();
        var telemetry = new MqttTeslaTelemetry(mqtt, TOPIC_BASE, VIN);
        telemetry.subscribeToMetrics(metricResults::add);
        telemetry.subscribeToConnectivity(connectivityResults::add);
    }

    @Test
    void metric_validField_deliveredAsSuccess() {
        mqtt.publish(metricTopic(TBatteryLevel.NAME), "67");
        assertThat(metricResults).singleElement().isEqualTo(new TelemetryResult.Success<>(new TBatteryLevelValue(67)));
    }

    @Test
    void metric_unsupportedField_notDelivered() {
        mqtt.publish(metricTopic("NoSuchField"), "1");
        assertThat(metricResults).isEmpty();
    }

    @Test
    void metric_undecodableData_deliveredAsErrorCarryingRedactedVinAndCauseButNoRawData() {
        mqtt.publish(metricTopic(TBatteryLevel.NAME), "not-a-number");
        assertThat(metricResults).singleElement()
                                 .isInstanceOfSatisfying(TelemetryResult.Error.class, error -> {
                                     assertThat(error.message()).contains(TBatteryLevel.NAME)
                                                                .contains(REDACTED_VIN)
                                                                .doesNotContain(VIN)
                                                                .doesNotContain("not-a-number");
                                     assertThat(error.cause()).isNotNull();
                                 });
    }

    @Test
    void metric_malformedTopic_deliveredAsErrorWithoutCauseOrClearVin() {
        mqtt.publish(TOPIC_BASE + '/' + VIN + "/v/", "1");
        assertThat(metricResults).singleElement()
                                 .isEqualTo(new TelemetryResult.Error<TelemetryField>("Unexpected telemetry topic structure for " + REDACTED_VIN, null));
    }

    @Test
    void connectivity_validEvent_deliveredAsSuccess() {
        TelemetryConnectivityEvent event = TelemetryConnectivityEvent.builder()
                                                                     .setCreatedAt(Instant.parse("2020-01-01T00:00:00Z"))
                                                                     .setStatus(CONNECTED)
                                                                     .build();
        mqtt.publish(connectivityTopic(), Json.stringify(event));
        assertThat(connectivityResults).singleElement().isEqualTo(new TelemetryResult.Success<>(event));
    }

    @Test
    void connectivity_undecodableData_deliveredAsErrorCarryingRedactedVin() {
        mqtt.publish(connectivityTopic(), "}{ not json");
        assertThat(connectivityResults).singleElement()
                                       .isInstanceOfSatisfying(TelemetryResult.Error.class, error -> {
                                           assertThat(error.message()).contains(REDACTED_VIN).doesNotContain(VIN);
                                           assertThat(error.cause()).isNotNull();
                                       });
    }

    private static String metricTopic(String fieldName) {
        return TOPIC_BASE + '/' + VIN + "/v/" + fieldName;
    }

    private static String connectivityTopic() {
        return TOPIC_BASE + '/' + VIN + "/connectivity";
    }
}
