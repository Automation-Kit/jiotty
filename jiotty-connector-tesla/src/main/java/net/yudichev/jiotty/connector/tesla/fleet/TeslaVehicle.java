package net.yudichev.jiotty.connector.tesla.fleet;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;

public interface TeslaVehicle {
    CompletableFuture<Void> wakeUp();

    CompletableFuture<CommandResult> setChargeLimit(int limitPercent);

    CompletableFuture<CommandResult> startCharge();

    CompletableFuture<CommandResult> stopCharge();

    CompletableFuture<CommandResult> startAutoConditioning();

    CompletableFuture<CommandResult> stopAutoConditioning();

    CompletableFuture<TelemetryGetConfigResponse> telemetryGetConfig();

    CompletableFuture<TelemetryDeleteConfigResponse> telemetryDeleteConfig();

    CompletableFuture<TelemetryFleetStatus> telemetryFleetStatus();

    CompletableFuture<TelemetryFleetErrorsResponse> telemetryFleetErrors();

    /// @return [Optional#empty()] if the vehicle is not online
    CompletableFuture<Optional<VehicleData>> getData(Set<Endpoint> endpoints);

    String vin();

    /// Tesla's answer to a vehicle command.
    sealed interface CommandResult permits CommandResult.Accepted, CommandResult.Refused, CommandResult.Unavailable {
        static CommandResult accepted() {
            return Accepted.INSTANCE;
        }

        final class Accepted implements CommandResult {
            static final Accepted INSTANCE = new Accepted();

            private Accepted() {
            }

            @Override
            public String toString() {
                return "accepted";
            }
        }

        /// A command the vehicle declined.
        ///
        /// @param reason Tesla's reason as it sends it, which can prefix its code, as in `car could not execute command: disconnected`; `unknown reason`
        ///               when Tesla gives none
        record Refused(String reason) implements CommandResult {
            public Refused {
                checkNotNull(reason);
            }
        }

        /// A command that did not reach the vehicle.
        ///
        /// @param error Tesla's description of why, such as `vehicle unavailable: vehicle is offline or asleep`
        record Unavailable(String error) implements CommandResult {
            public Unavailable {
                checkNotNull(error);
            }
        }
    }

    enum Endpoint {
        CHARGE_STATE("charge_state"),
        CLIMATE_STATE("climate_state"),
        CLOSURES_STATE("closures_state"),
        DRIVE_STATE("drive_state"),
        GUI_SETTINGS("gui_settings"),
        LOCATION_DATA("location_data"),
        CHARGE_SCHEDULE_DATA("charge_schedule_data"),
        PRECONDITIONING_SCHEDULE_DATA("preconditioning_schedule_data"),
        VEHICLE_CONFIG("vehicle_config"),
        VEHICLE_STATE("vehicle_state"),
        VEHICLE_DATA_COMBO("vehicle_data_combo"),
        ;

        private final String id;

        Endpoint(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }
}
