package net.yudichev.jiotty.connector.mqtt;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

final class MqttClientProvider implements Provider<IMqttAsyncClient> {
    private static final Logger logger = LogManager.getLogger(MqttClientProvider.class);
    private final String serverUri;
    private final String clientId;

    @Inject
    MqttClientProvider(@ServerUri String serverUri,
                       @ClientId String clientId) {
        this.serverUri = checkNotNull(serverUri);
        this.clientId = checkNotNull(clientId);
    }

    @Override
    public IMqttAsyncClient get() {
        logger.info("Creating MQTT client for {} as {}", serverUri, clientId);
        return getAsUnchecked(() -> new MqttAsyncClient(serverUri, clientId, new MemoryPersistence()));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ServerUri {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ClientId {
    }
}
