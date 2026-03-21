package net.yudichev.jiotty.connector.ir.binary;

import com.github.mob41.blapi.BLDevice;
import com.github.mob41.blapi.RM2Device;
import com.github.mob41.blapi.mac.Mac;
import com.github.mob41.blapi.pkt.cmd.rm2.SendDataCmdPayload;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

final class BroadLinkIrDevice extends BaseLifecycleComponent implements IrDevice {
    private static final Logger logger = LogManager.getLogger(BroadLinkIrDevice.class);
    private static final int INIT_ATTEMPTS = 3;
    private final String host;
    private final String macAddress;
    private final DeviceSupplier deviceSupplier;
    private BLDevice device;

    @Inject
    BroadLinkIrDevice(@Host String host, @MacAddress String macAddress) {
        this(host, macAddress, RM2Device::new);
    }

    BroadLinkIrDevice(String host, String macAddress, DeviceSupplier deviceSupplier) {
        this.host = checkNotNull(host);
        this.macAddress = checkNotNull(macAddress);
        this.deviceSupplier = checkNotNull(deviceSupplier);
    }


    @SuppressWarnings("AssignmentToNull")
    @Override
    public void doStart() {
        int attempts = INIT_ATTEMPTS;
        while (device == null) {
            try {
                device = deviceSupplier.create(host, new Mac(macAddress));
                if (device.auth()) {
                    logger.info("RM2 Device ready at {}: {}", device.getHost(), device.getDeviceDescription());
                } else {
                    device.close();
                    device = null;
                    attempts--;
                    if (attempts == 0) {
                        throw new RuntimeException(
                                String.format("Failed to authenticate Broadlink device on host %s, MAC %s after %s attempts",
                                              host, macAddress, INIT_ATTEMPTS));
                    } else {
                        logger.info("Unable to authenticate Broadlink device on host {}, MAC {}, will retry {} more time(s)", host, macAddress, attempts);
                    }
                }
            } catch (IOException e) {
                if (device != null) {
                    device.close();
                    device = null;
                }
                attempts--;
                if (attempts == 0) {
                    throw new RuntimeException(
                            String.format("Failed to initialize Broadlink device on host %s, MAC %s after %s attempts", host, macAddress, INIT_ATTEMPTS),
                            e);
                } else {
                    logger.info("Unable to initialize Broadlink device on host {}, MAC {}, will retry {} more time(s)", host, macAddress, attempts, e);
                }
            }
        }
    }

    @Override
    public void sendCommandPacket(byte[] packetData) {
        whenStartedAndNotLifecycling(() -> asUnchecked(() -> device.sendCmdPkt(new SendDataCmdPayload(packetData))));
    }

    @Override
    protected void doStop() {
        device.close();
    }

    interface DeviceSupplier {
        BLDevice create(String host, Mac mac) throws IOException;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Host {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface MacAddress {
    }
}
