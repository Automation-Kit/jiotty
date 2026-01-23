package net.yudichev.jiotty.connector.tesla.fleet;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TInsideTempValue;

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
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TLocation;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TLocationValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TVehicleSpeed;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TVehicleSpeedValue;

final class TelemetryFieldDecoder {
    /// @return `null` if the `fieldName` is unsupported
    /// @implNote MQTT dispatcher maps Invalid to null:
    /// [code](https://github.com/teslamotors/fleet-telemetry/blob/031553a7d3d6952c1552ed13dc71aaf4fd4a882b/datastore/mqtt/mqtt_payload.go#L180). [The
    /// documentation](https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data) says that invalid means "the vehicle has entered a state where
    /// that signal cannot be accurately measured or is otherwise invalid."
    public static @Nullable TelemetryField decode(String fieldName, String jsonData) {
        return switch (fieldName) {
            case TDetailedChargeState.NAME -> TDetailedChargeState.decode(jsonData);
            case TBatteryLevel.NAME -> decodeBatteryLevel(jsonData);
            case TChargeLimitSoc.NAME -> decodeChargeLimitSoc(jsonData);
            case TLocation.NAME -> decodeLocation(Json.parse(jsonData, TelemetryLocation.class));
            case THvacPower.NAME -> THvacPower.decode(jsonData);
            case TInsideTemp.NAME -> decodeInsideTemp(jsonData);
            case THvacLeftTemperatureRequest.NAME -> decodeHvacLeftTemperatureRequest(jsonData);
            case THvacRightTemperatureRequest.NAME -> decodeHvacRightTemperatureRequest(jsonData);
            case TVehicleSpeed.NAME -> decodeVehicleSpeed(jsonData);
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

    static TBatteryLevel decodeBatteryLevel(String jsonData) {
        return "null".equals(jsonData) ? TBatteryLevel.INVALID : new TBatteryLevelValue(decodeDouble(jsonData));
    }

    public static TLocation decodeLocation(@Nullable TelemetryLocation jsonValue) {
        return jsonValue == null ? TLocation.INVALID : new TLocationValue(new LatLon(jsonValue.latitude(), jsonValue.longitude()));
    }

    private static TChargeLimitSoc decodeChargeLimitSoc(String jsonData) {
        return "null".equals(jsonData) ? TChargeLimitSoc.INVALID : new TChargeLimitSocValue(decodeInt(jsonData));
    }

    static TInsideTemp decodeInsideTemp(String jsonData) {
        return "null".equals(jsonData) ? TInsideTemp.INVALID : new TInsideTempValue(decodeDouble(jsonData));
    }

    static THvacLeftTemperatureRequest decodeHvacLeftTemperatureRequest(String jsonData) {
        return "null".equals(jsonData) ? THvacLeftTemperatureRequest.INVALID : new THvacLeftTemperatureRequestValue(decodeDouble(jsonData));
    }

    static THvacRightTemperatureRequest decodeHvacRightTemperatureRequest(String jsonData) {
        return "null".equals(jsonData) ? THvacRightTemperatureRequest.INVALID : new THvacRightTemperatureRequestValue(decodeDouble(jsonData));
    }

    static TVehicleSpeed decodeVehicleSpeed(String jsonData) {
        return "null".equals(jsonData) ? TVehicleSpeed.INVALID : new TVehicleSpeedValue(decodeDouble(jsonData));
    }

}
