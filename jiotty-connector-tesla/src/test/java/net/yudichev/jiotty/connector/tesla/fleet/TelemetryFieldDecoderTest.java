package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.geo.LatLon;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TBatteryLevel;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TChargeLimitSoc;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TDetailedChargeState;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TDriveRail;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TGear;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacLeftTemperatureRequest;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacPower;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacRightTemperatureRequest;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TInsideTemp;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TLocation;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TVehicleSpeed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class TelemetryFieldDecoderTest {
    @ParameterizedTest
    @MethodSource("provideDecodeTestData")
    void decodesField(String fieldName, String jsonData, TelemetryField expectedField) {
        TelemetryField field = TelemetryFieldDecoder.decode(fieldName, jsonData);
        assertThat(field).isEqualTo(expectedField);
    }

    static Stream<Arguments> provideDecodeTestData() {
        return Stream.of(
                arguments(TDetailedChargeState.NAME, "\"DetailedChargeStateCharging\"", TDetailedChargeState.CHARGING),
                arguments(TBatteryLevel.NAME, "67", new TBatteryLevel(67)),
                arguments(TChargeLimitSoc.NAME, "80", new TChargeLimitSoc(80)),
                arguments(TLocation.NAME, "{\"latitude\": 1.1, \"longitude\": 2.2}", new TLocation(new LatLon(1.1, 2.2))),
                arguments(THvacPower.NAME, "\"HvacPowerStateOn\"", THvacPower.ON),
                arguments(TInsideTemp.NAME, "21.5", new TInsideTemp(21.5)),
                arguments(THvacLeftTemperatureRequest.NAME, "20.0", new THvacLeftTemperatureRequest(20.0)),
                arguments(THvacRightTemperatureRequest.NAME, "22.0", new THvacRightTemperatureRequest(22.0)),
                arguments(TVehicleSpeed.NAME, "10.5", new TVehicleSpeed(10.5)),
                arguments(TGear.NAME, "\"ShiftStateD\"", TGear.D),
                arguments(TGear.NAME, "\"ShiftStateInvalid\"", TGear.INVALID),
                arguments(TGear.NAME, "\"SomeUnknownValue\"", TGear.UNKNOWN),
                arguments(TDriveRail.NAME, "true", TDriveRail.ON),
                arguments(TDriveRail.NAME, "false", TDriveRail.OFF)
        );
    }
}
