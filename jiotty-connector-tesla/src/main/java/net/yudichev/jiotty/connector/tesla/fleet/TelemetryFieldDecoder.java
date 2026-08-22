package net.yudichev.jiotty.connector.tesla.fleet;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TInsideTempValue;
import org.jspecify.annotations.Nullable;

import java.util.function.DoubleFunction;

import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TACChargingEnergyIn;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TACChargingEnergyInValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TBatteryLevel;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TBatteryLevelValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TChargeLimitSoc;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TChargeLimitSocValue;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TDCChargingEnergyIn;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TDCChargingEnergyInValue;
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
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TOdometer;
import static net.yudichev.jiotty.connector.tesla.fleet.TelemetryField.TOdometerValue;
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
            case TOdometer.NAME -> decodeOdometer(jsonData);
            case TGear.NAME -> TGear.decode(jsonData);
            case TDriveRail.NAME -> TDriveRail.decode(jsonData);
            case TACChargingEnergyIn.NAME -> decodeAcChargingEnergyIn(jsonData);
            case TDCChargingEnergyIn.NAME -> decodeDcChargingEnergyIn(jsonData);
            default -> null;
        };
    }

    private static int decodeInt(String jsonData) {
        return Integer.parseInt(jsonData);
    }

    /// Every nullable double-valued field decodes the same way: the dispatcher's literal `null` means the signal is invalid, anything else is a bare double.
    private static <T> T decodeDoubleField(String jsonData, T invalidValue, DoubleFunction<T> valueFactory) {
        return "null".equals(jsonData) ? invalidValue : valueFactory.apply(Double.parseDouble(jsonData));
    }

    static TBatteryLevel decodeBatteryLevel(String jsonData) {
        return decodeDoubleField(jsonData, TBatteryLevel.INVALID, TBatteryLevelValue::new);
    }

    public static TLocation decodeLocation(@Nullable TelemetryLocation jsonValue) {
        return jsonValue == null ? TLocation.INVALID : new TLocationValue(new LatLon(jsonValue.latitude(), jsonValue.longitude()));
    }

    private static TChargeLimitSoc decodeChargeLimitSoc(String jsonData) {
        return "null".equals(jsonData) ? TChargeLimitSoc.INVALID : new TChargeLimitSocValue(decodeInt(jsonData));
    }

    static TInsideTemp decodeInsideTemp(String jsonData) {
        return decodeDoubleField(jsonData, TInsideTemp.INVALID, TInsideTempValue::new);
    }

    static THvacLeftTemperatureRequest decodeHvacLeftTemperatureRequest(String jsonData) {
        return decodeDoubleField(jsonData, THvacLeftTemperatureRequest.INVALID, THvacLeftTemperatureRequestValue::new);
    }

    static THvacRightTemperatureRequest decodeHvacRightTemperatureRequest(String jsonData) {
        return decodeDoubleField(jsonData, THvacRightTemperatureRequest.INVALID, THvacRightTemperatureRequestValue::new);
    }

    static TVehicleSpeed decodeVehicleSpeed(String jsonData) {
        return decodeDoubleField(jsonData, TVehicleSpeed.INVALID, TVehicleSpeedValue::new);
    }

    static TOdometer decodeOdometer(String jsonData) {
        return decodeDoubleField(jsonData, TOdometer.INVALID, TOdometerValue::new);
    }

    static TACChargingEnergyIn decodeAcChargingEnergyIn(String jsonData) {
        return decodeDoubleField(jsonData, TACChargingEnergyIn.INVALID, TACChargingEnergyInValue::new);
    }

    static TDCChargingEnergyIn decodeDcChargingEnergyIn(String jsonData) {
        return decodeDoubleField(jsonData, TDCChargingEnergyIn.INVALID, TDCChargingEnergyInValue::new);
    }
}
