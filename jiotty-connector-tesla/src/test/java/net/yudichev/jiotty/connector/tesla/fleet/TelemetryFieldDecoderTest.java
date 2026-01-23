package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.geo.LatLon;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TBatteryLevel;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TBatteryLevelValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TChargeLimitSoc;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TChargeLimitSocValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TDetailedChargeState;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TDriveRail;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TGear;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacLeftTemperatureRequest;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacLeftTemperatureRequestValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacPower;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacRightTemperatureRequest;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.THvacRightTemperatureRequestValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TInsideTemp;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TInsideTempValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TLocation;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TLocationValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TVehicleSpeed;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TVehicleSpeedValue;
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
                arguments(TDetailedChargeState.NAME, "null", TDetailedChargeState.UNKNOWN),
                arguments(TBatteryLevel.NAME, "67", new TBatteryLevelValue(67)),
                arguments(TBatteryLevel.NAME, "null", TBatteryLevel.INVALID),
                arguments(TChargeLimitSoc.NAME, "80", new TChargeLimitSocValue(80)),
                arguments(TChargeLimitSoc.NAME, "null", TChargeLimitSoc.INVALID),
                arguments(TLocation.NAME, "{\"latitude\": 1.1, \"longitude\": 2.2}", new TLocationValue(new LatLon(1.1, 2.2))),
                arguments(TLocation.NAME, "null", TLocation.INVALID),
                arguments(THvacPower.NAME, "\"HvacPowerStateOn\"", THvacPower.ON),
                arguments(THvacPower.NAME, "null", THvacPower.UNKNOWN),
                arguments(TInsideTemp.NAME, "21.5", new TInsideTempValue(21.5)),
                arguments(TInsideTemp.NAME, "null", TInsideTemp.INVALID),
                arguments(THvacLeftTemperatureRequest.NAME, "20.0", new THvacLeftTemperatureRequestValue(20.0)),
                arguments(THvacLeftTemperatureRequest.NAME, "null", THvacLeftTemperatureRequest.INVALID),
                arguments(THvacRightTemperatureRequest.NAME, "22.0", new THvacRightTemperatureRequestValue(22.0)),
                arguments(THvacRightTemperatureRequest.NAME, "null", THvacRightTemperatureRequest.INVALID),
                arguments(TVehicleSpeed.NAME, "10.5", new TVehicleSpeedValue(10.5)),
                arguments(TVehicleSpeed.NAME, "null", TVehicleSpeed.INVALID),
                arguments(TGear.NAME, "\"ShiftStateD\"", TGear.D),
                arguments(TGear.NAME, "null", TGear.UNKNOWN),
                arguments(TGear.NAME, "\"ShiftStateInvalid\"", TGear.INVALID),
                arguments(TGear.NAME, "\"SomeUnknownValue\"", TGear.UNKNOWN),
                arguments(TDriveRail.NAME, "true", TDriveRail.ON),
                arguments(TDriveRail.NAME, "false", TDriveRail.OFF),
                arguments(TDriveRail.NAME, "null", TDriveRail.UNKNOWN)
        );
    }
}
