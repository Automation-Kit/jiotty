package net.yudichev.jiotty.connector.google.sdm;

import com.google.api.services.smartdevicemanagement.v1.SmartDeviceManagement;
import com.google.api.services.smartdevicemanagement.v1.model.GoogleHomeEnterpriseSdmV1Device;
import com.google.api.services.smartdevicemanagement.v1.model.GoogleHomeEnterpriseSdmV1ExecuteDeviceCommandRequest;
import com.google.common.collect.ImmutableMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

final class InternalGoogleNestThermostat implements GoogleNestThermostat {
    private static final Logger logger = LogManager.getLogger(InternalGoogleNestThermostat.class);

    private final SmartDeviceManagement smartDeviceManagement;
    private final Executor executor;
    private final String deviceName;

    InternalGoogleNestThermostat(SmartDeviceManagement smartDeviceManagement, String projectId, String deviceId, Executor executor) {
        this.smartDeviceManagement = checkNotNull(smartDeviceManagement);
        this.executor = checkNotNull(executor);
        deviceName = "enterprises/" + projectId + "/devices/" + deviceId;
    }


    @Override
    public CompletableFuture<Mode> getCurrentMode() {
        return CompletableFuture.supplyAsync(() -> getAsUnchecked(this::doGetCurrentMode), executor);
    }

    @Override
    public CompletableFuture<Void> setMode(Mode mode, boolean verify) {
        return getCurrentMode().thenAccept(currentMode ->
                                                   asUnchecked(() -> {
                                                       if (mode == currentMode) {
                                                           logger.debug("already in {}, will do nothing", mode);
                                                           return;
                                                       }
                                                       if (mode == Mode.ECO) {
                                                           setEcoMode("MANUAL_ECO");
                                                       } else {
                                                           if (currentMode == Mode.ECO) {
                                                               // must first switch off ECO, otherwise thermostat remains on ECO and the main trait is ignored
                                                               setEcoMode("OFF");
                                                           }
                                                           GoogleHomeEnterpriseSdmV1ExecuteDeviceCommandRequest request =
                                                                   new GoogleHomeEnterpriseSdmV1ExecuteDeviceCommandRequest();
                                                           request.setCommand("sdm.devices.commands.ThermostatMode.SetMode");
                                                           request.setParams(ImmutableMap.of("mode", mode.name()));
                                                           executeRequest(request);

                                                           // verify
                                                           if (verify) {
                                                               Mode actualMode = doGetCurrentMode();
                                                               checkState(actualMode == mode,
                                                                          "Mode verification failed, requested %s, but it's still %s",
                                                                          mode,
                                                                          actualMode);
                                                           }
                                                       }
                                                   }));
    }

    private Mode doGetCurrentMode() throws IOException {
        logger.debug("devices.get({})...", deviceName);
        GoogleHomeEnterpriseSdmV1Device device = smartDeviceManagement.enterprises().devices().get(deviceName).execute();
        logger.debug("... {}", device);
        Map<String, String> modeTrait = (Map<String, String>) device.getTraits().get("sdm.devices.traits.ThermostatMode");
        checkState(modeTrait != null, "Thermostat does not support modes");
        Map<String, String> ecoTrait = (Map<String, String>) device.getTraits().get("sdm.devices.traits.ThermostatEco");
        if (ecoTrait != null) {
            if ("MANUAL_ECO".equals(ecoTrait.get("mode"))) {
                return Mode.ECO;
            }
        }
        return Mode.valueOf(modeTrait.get("mode"));
    }

    private void setEcoMode(String ecoMode) throws IOException {
        GoogleHomeEnterpriseSdmV1ExecuteDeviceCommandRequest request = new GoogleHomeEnterpriseSdmV1ExecuteDeviceCommandRequest();
        request.setCommand("sdm.devices.commands.ThermostatEco.SetMode");
        request.setParams(ImmutableMap.of("mode", ecoMode));
        executeRequest(request);
    }

    private void executeRequest(GoogleHomeEnterpriseSdmV1ExecuteDeviceCommandRequest request) throws IOException {
        logger.debug("devices.executeCommand({}, {})", deviceName, request);
        smartDeviceManagement.enterprises().devices().executeCommand(deviceName, request).execute();
    }
}
