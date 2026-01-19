package net.yudichev.jiotty.connector.tesla.fleet;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.lang.Json;

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

final class TelemetryFieldDecoder {
    /// @return `null` if the `fieldName` is unsupported
    public static @Nullable TelemetryField decode(String fieldName, String jsonData) {
        return switch (fieldName) {
            case TDetailedChargeState.NAME -> TDetailedChargeState.decode(jsonData);
            case TBatteryLevel.NAME -> new TBatteryLevel(decodeDouble(jsonData));
            case TChargeLimitSoc.NAME -> new TChargeLimitSoc(decodeInt(jsonData));
            case TLocation.NAME -> TLocation.decode(Json.parse(jsonData, TelemetryLocation.class));
            case THvacPower.NAME -> THvacPower.decode(jsonData);
            case TInsideTemp.NAME -> new TInsideTemp(decodeDouble(jsonData));
            case THvacLeftTemperatureRequest.NAME -> new THvacLeftTemperatureRequest(decodeDouble(jsonData));
            case THvacRightTemperatureRequest.NAME -> new THvacRightTemperatureRequest(decodeDouble(jsonData));
            case TVehicleSpeed.NAME -> new TVehicleSpeed(decodeDouble(jsonData));
            case TGear.NAME -> TGear.decode(jsonData);
            case TDriveRail.NAME -> TDriveRail.decode(jsonData);
            default -> null;
        };
    }

    private static int decodeInt(String jsonData) {
        return Integer.parseInt(jsonData);
    }

    private static double decodeDouble(String jsonData) {
        return Double.parseDouble(jsonData);
    }
}
