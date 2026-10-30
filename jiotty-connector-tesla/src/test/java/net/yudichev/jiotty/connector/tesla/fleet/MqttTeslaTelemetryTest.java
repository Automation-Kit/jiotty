package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.geo.LatLon;
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
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TLocation;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TLocationValue;
import static org.assertj.core.api.Assertions.assertThat;

class MqttTeslaTelemetryTest {
    private static final String VIN = "5YJ3E1EA1JF000001";
    private static final String REDACTED_VIN = "5YJ…";
    private static final String TOPIC_BASE = "telemetry";

    private final List<TelemetryResult<TelemetryField>> metricResults = new ArrayList<>();
    private final List<TelemetryResult<TelemetryConnectivityEvent>> connectivityResults = new ArrayList<>();
    private InMemoryMqtt mqtt;
    private MqttTeslaTelemetry telemetry;

    @BeforeEach
    void setUp() {
        mqtt = new InMemoryMqtt();
        telemetry = new MqttTeslaTelemetry(mqtt, TOPIC_BASE, VIN);
        telemetry.subscribeToMetrics(metricResults::add);
        telemetry.subscribeToConnectivity(connectivityResults::add);
    }

    @Test
    void metric_validField_deliveredAsSuccess() {
        mqtt.publish(metricTopic(TBatteryLevel.NAME), "67");
        assertThat(metricResults).singleElement().isEqualTo(new TelemetryResult.Success<>(new TBatteryLevelValue(67), false));
    }

    @Test
    void metric_heldBeforeSubscribing_deliveredAsReplayed() {
        mqtt.publish(metricTopic(TBatteryLevel.NAME), "67");
        List<TelemetryResult<TelemetryField>> laterSubscriberResults = new ArrayList<>();

        telemetry.subscribeToMetrics(laterSubscriberResults::add);

        assertThat(laterSubscriberResults).singleElement().isEqualTo(new TelemetryResult.Success<>(new TBatteryLevelValue(67), true));
    }

    /// The car's position is the most sensitive thing this connector carries, so its payload gets a case of its own — the PII log guard sees a leak only in a
    /// line a test actually renders.
    @Test
    void metric_location_deliveredAsSuccess() {
        mqtt.publish(metricTopic(TLocation.NAME), "{\"latitude\":51.501234,\"longitude\":-0.142567}");

        assertThat(metricResults).singleElement().isEqualTo(new TelemetryResult.Success<>(new TLocationValue(new LatLon(51.501234, -0.142567)), false));
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
        TelemetryConnectivityEvent event = createConnectedEvent();
        mqtt.publish(connectivityTopic(), Json.stringify(event));
        assertThat(connectivityResults).singleElement().isEqualTo(new TelemetryResult.Success<>(event, false));
    }

    @Test
    void connectivity_heldBeforeSubscribing_deliveredAsReplayed() {
        TelemetryConnectivityEvent event = createConnectedEvent();
        mqtt.publish(connectivityTopic(), Json.stringify(event));
        List<TelemetryResult<TelemetryConnectivityEvent>> laterSubscriberResults = new ArrayList<>();

        telemetry.subscribeToConnectivity(laterSubscriberResults::add);

        assertThat(laterSubscriberResults).singleElement().isEqualTo(new TelemetryResult.Success<>(event, true));
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

    @Test
    void clearRetained_removesThisVehiclesMetricsAndConnectivityOnly() {
        String otherVehicleTopic = TOPIC_BASE + "/5YJ3E1EA1JF000002/v/" + TLocation.NAME;
        mqtt.publish(metricTopic(TBatteryLevel.NAME), "67");
        mqtt.publish(metricTopic(TLocation.NAME), "{\"latitude\":51.501234,\"longitude\":-0.142567}");
        mqtt.publish(connectivityTopic(), "{}");
        mqtt.publish(otherVehicleTopic, "{}");

        assertThat(telemetry.clearRetained()).isCompleted();

        assertThat(mqtt.retainedMessage(metricTopic(TBatteryLevel.NAME))).isEmpty();
        assertThat(mqtt.retainedMessage(metricTopic(TLocation.NAME))).isEmpty();
        assertThat(mqtt.retainedMessage(connectivityTopic())).isEmpty();
        assertThat(mqtt.retainedMessage(otherVehicleTopic)).hasValue("{}");
    }

    private static String metricTopic(String fieldName) {
        return TOPIC_BASE + '/' + VIN + "/v/" + fieldName;
    }

    private static TelemetryConnectivityEvent createConnectedEvent() {
        return TelemetryConnectivityEvent.builder().setCreatedAt(Instant.parse("2020-01-01T00:00:00Z")).setStatus(CONNECTED).build();
    }

    private static String connectivityTopic() {
        return TOPIC_BASE + '/' + VIN + "/connectivity";
    }
}
