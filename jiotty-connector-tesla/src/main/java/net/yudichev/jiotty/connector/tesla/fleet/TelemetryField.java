package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.common.collect.ImmutableSet;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.MoreThrowables;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.jspecify.annotations.Nullable;

import java.util.stream.Stream;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

public sealed interface TelemetryField permits
        TelemetryField.TDetailedChargeState,
        TelemetryField.TBatteryLevel,
        TelemetryField.TChargeLimitSoc,
        TelemetryField.TLocation,
        TelemetryField.THvacPower,
        TelemetryField.TInsideTemp,
        TelemetryField.THvacLeftTemperatureRequest,
        TelemetryField.THvacRightTemperatureRequest,
        TelemetryField.TVehicleSpeed,
        TelemetryField.TOdometer,
        TelemetryField.TGear,
        TelemetryField.TDriveRail,
        TelemetryField.TACChargingEnergyIn,
        TelemetryField.TDCChargingEnergyIn {

    @SuppressWarnings("PublicStaticCollectionField") // immutable
    ImmutableSet<String> ALL_NAMES = Stream.of(TelemetryField.class.getPermittedSubclasses())
                                           .map(clazz -> MoreThrowables.getAsUnchecked(() -> (String) clazz.getDeclaredField("NAME").get(null)))
                                           .collect(toImmutableSet());

    String fieldName();

    enum THvacPower implements TelemetryField, StringFormattable {
        UNKNOWN,
        OFF,
        ON,
        PRECONDITION,
        OVERHEAT_PROTECT;

        public static final String NAME = "HvacPower";

        public static THvacPower decode(String jsonValue) {
            return switch (Json.parse(jsonValue, String.class)) {
                case "HvacPowerStateOff" -> OFF;
                case "HvacPowerStateOn" -> ON;
                case "HvacPowerStatePrecondition" -> PRECONDITION;
                case "HvacPowerStateOverheatProtect" -> OVERHEAT_PROTECT;
                case null, default -> UNKNOWN;
            };
        }

        @Override
        public String fieldName() {
            return NAME;
        }

        @Override
        public String toString() {
            return toString(32);
        }

        // To look consistent with the other fields
        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "THvacPower[");
            Append.to(appendable, name());
            Append.to(appendable, ']');
        }
    }

    enum TGear implements TelemetryField, StringFormattable {
        UNKNOWN,
        INVALID,
        P,
        R,
        N,
        D,
        SNA;

        public static final String NAME = "Gear";

        public static TGear decode(String jsonValue) {
            return switch (Json.parse(jsonValue, String.class)) {
                case "ShiftStateP" -> P;
                case "ShiftStateR" -> R;
                case "ShiftStateN" -> N;
                case "ShiftStateD" -> D;
                case "ShiftStateSNA" -> SNA;
                case "ShiftStateInvalid" -> INVALID;
                case null, default -> UNKNOWN;
            };
        }

        @Override
        public String fieldName() {
            return NAME;
        }

        @Override
        public String toString() {
            return toString(16);
        }

        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "TGear[");
            Append.to(appendable, name());
            Append.to(appendable, ']');
        }
    }

    enum TDriveRail implements TelemetryField, StringFormattable {
        UNKNOWN,
        OFF,
        ON;

        public static final String NAME = "DriveRail";

        public static TDriveRail decode(String jsonValue) {
            return switch (jsonValue) {
                case "true" -> ON;
                case "false" -> OFF;
                default -> UNKNOWN;
            };
        }

        @Override
        public String fieldName() {
            return NAME;
        }

        @Override
        public String toString() {
            return toString(32);
        }

        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "TDriveRail[");
            Append.to(appendable, name());
            Append.to(appendable, ']');
        }
    }

    sealed interface TBatteryLevel extends TelemetryField permits Invalid, TBatteryLevelValue {

        String NAME = "BatteryLevel";
        TBatteryLevel INVALID = new Invalid(NAME);
    }

    sealed interface TLocation extends TelemetryField permits Invalid, TLocationValue {
        String NAME = "Location";
        TLocation INVALID = new Invalid(NAME);
    }

    sealed interface TChargeLimitSoc extends TelemetryField permits TChargeLimitSocValue, Invalid {
        String NAME = "ChargeLimitSoc";
        TChargeLimitSoc INVALID = new Invalid(NAME);
    }

    sealed interface TInsideTemp extends TelemetryField permits TInsideTempValue, Invalid {
        String NAME = "InsideTemp";
        TInsideTemp INVALID = new Invalid(NAME);
    }

    sealed interface THvacLeftTemperatureRequest extends TelemetryField permits THvacLeftTemperatureRequestValue, Invalid {
        String NAME = "HvacLeftTemperatureRequest";
        THvacLeftTemperatureRequest INVALID = new Invalid(NAME);
    }

    sealed interface THvacRightTemperatureRequest extends TelemetryField permits THvacRightTemperatureRequestValue, Invalid {
        String NAME = "HvacRightTemperatureRequest";
        THvacRightTemperatureRequest INVALID = new Invalid(NAME);
    }

    sealed interface TVehicleSpeed extends TelemetryField permits TVehicleSpeedValue, Invalid {
        String NAME = "VehicleSpeed";
        TVehicleSpeed INVALID = new Invalid(NAME);
    }

    /// Lifetime distance travelled, in **miles** — the unit Tesla reports it in.
    sealed interface TOdometer extends TelemetryField permits TOdometerValue, Invalid {
        String NAME = "Odometer";
        TOdometer INVALID = new Invalid(NAME);
    }

    /// Per-charging-session counter, kWh, measured from the AC charger. Tesla docs note: ignore during DC charging. Per-session cumulative — resets when a new
    /// session begins.
    sealed interface TACChargingEnergyIn extends TelemetryField permits TACChargingEnergyInValue, Invalid {
        String NAME = "ACChargingEnergyIn";
        TACChargingEnergyIn INVALID = new Invalid(NAME);
    }

    /// Per-charging-session counter, kWh, measured at the battery. Tesla docs note: reliable for both AC and DC charging. Maps to the legacy
    /// `charge_state.charge_energy_added` field in the polling API.
    sealed interface TDCChargingEnergyIn extends TelemetryField permits TDCChargingEnergyInValue, Invalid {
        String NAME = "DCChargingEnergyIn";
        TDCChargingEnergyIn INVALID = new Invalid(NAME);
    }

    record Invalid(String fieldName)
            implements TBatteryLevel, TLocation, TChargeLimitSoc, TInsideTemp, THvacLeftTemperatureRequest, THvacRightTemperatureRequest, TVehicleSpeed,
            TOdometer, TACChargingEnergyIn, TDCChargingEnergyIn, StringFormattable {
        @Override
        public String toString() {
            return toString(48);
        }

        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, fieldName);
            Append.to(appendable, "[INVALID]");
        }
    }

    record TDetailedChargeState(TeslaChargingState state) implements TelemetryField {
        public static final TDetailedChargeState DISCONNECTED = new TDetailedChargeState(TeslaChargingState.DISCONNECTED);
        public static final TDetailedChargeState NO_POWER = new TDetailedChargeState(TeslaChargingState.NO_POWER);
        public static final TDetailedChargeState STARTING = new TDetailedChargeState(TeslaChargingState.STARTING);
        public static final TDetailedChargeState CHARGING = new TDetailedChargeState(TeslaChargingState.CHARGING);
        public static final TDetailedChargeState COMPLETE = new TDetailedChargeState(TeslaChargingState.COMPLETE);
        public static final TDetailedChargeState STOPPED = new TDetailedChargeState(TeslaChargingState.STOPPED);
        public static final TDetailedChargeState UNKNOWN = new TDetailedChargeState(TeslaChargingState.UNKNOWN);
        public static final String NAME = "DetailedChargeState";

        public static TDetailedChargeState decode(@Nullable String jsonValue) {
            return switch (Json.parse(jsonValue, String.class)) {
                case "DetailedChargeStateDisconnected" -> DISCONNECTED;
                case "DetailedChargeStateNoPower" -> NO_POWER;
                case "DetailedChargeStateStarting" -> STARTING;
                case "DetailedChargeStateCharging" -> CHARGING;
                case "DetailedChargeStateComplete" -> COMPLETE;
                case "DetailedChargeStateStopped" -> STOPPED;
                case null, default -> UNKNOWN;
            };
        }

        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record TBatteryLevelValue(double value) implements TBatteryLevel {

        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record TChargeLimitSocValue(int soc) implements TChargeLimitSoc {

        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record TLocationValue(LatLon latLon) implements TLocation {
        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record TInsideTempValue(double value) implements TInsideTemp {
        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record THvacLeftTemperatureRequestValue(double value) implements THvacLeftTemperatureRequest {
        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record THvacRightTemperatureRequestValue(double value) implements THvacRightTemperatureRequest {
        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record TVehicleSpeedValue(double value) implements TVehicleSpeed {
        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record TOdometerValue(double value) implements TOdometer {
        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record TACChargingEnergyInValue(double value) implements TACChargingEnergyIn {
        @Override
        public String fieldName() {
            return NAME;
        }
    }

    record TDCChargingEnergyInValue(double value) implements TDCChargingEnergyIn {
        @Override
        public String fieldName() {
            return NAME;
        }
    }
}
