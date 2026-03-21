package net.yudichev.jiotty.connector.google.sdm;

import com.google.api.services.smartdevicemanagement.v1.SmartDeviceManagement;
import com.google.api.services.smartdevicemanagement.v1.model.GoogleHomeEnterpriseSdmV1ListDevicesResponse;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

final class GoogleSmartDeviceManagementClientImpl implements GoogleSmartDeviceManagementClient {
    private static final Logger logger = LogManager.getLogger(GoogleSmartDeviceManagementClientImpl.class);

    private final SmartDeviceManagement smartDeviceManagement;
    private final String projectId;

    @Inject
    GoogleSmartDeviceManagementClientImpl(@Dependency SmartDeviceManagement smartDeviceManagement,
                                          @ProjectId String projectId) {
        this.smartDeviceManagement = checkNotNull(smartDeviceManagement);
        this.projectId = checkNotNull(projectId);
    }

    @Override
    public CompletableFuture<List<SmartDevice>> listDevices(Executor executor) {
        return CompletableFuture.supplyAsync(() -> getAsUnchecked(() -> {
            String parent = "enterprises/" + projectId;
            logger.debug("devices.list({})...", parent);
            GoogleHomeEnterpriseSdmV1ListDevicesResponse devicesResponse = smartDeviceManagement.enterprises().devices().list(parent).execute();
            logger.debug("... {}", devicesResponse);
            return devicesResponse.getDevices().stream().map(device -> SmartDevice.of(device.getName(), device.getType())).collect(toImmutableList());
        }), executor);
    }

    @Override
    public GoogleNestThermostat getThermostat(String deviceId, Executor executor) {
        return new InternalGoogleNestThermostat(smartDeviceManagement, projectId, deviceId, executor);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ProjectId {
    }
}
