package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.inject.BindingAnnotation;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.connector.mqtt.Mqtt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaTelemetry.BrokerConnectionStatus.CONNECTED;
import static net.yudichev.jiotty.connector.tesla.fleet.TeslaTelemetry.BrokerConnectionStatus.DISCONNECTED;

/// Feeds data from the MQTT dispatcher of the [Tesla Fleet Telemetry Server](https://github.com/teslamotors/fleet-telemetry)
public final class MqttTeslaTelemetry implements TeslaTelemetry {
    private static final Logger logger = LoggerFactory.getLogger(MqttTeslaTelemetry.class);

    private final Mqtt mqtt;
    private final String vin;
    private final String metricsTopicFilter;
    private final String connectivityTopicFilter;

    @Inject
    public MqttTeslaTelemetry(@Dependency Mqtt mqtt,
                              @TopicBase String topicBase,
                              @Assisted("vin") String vin) {
        this.mqtt = checkNotNull(mqtt);
        this.vin = checkNotNull(vin);
        metricsTopicFilter = topicBase + '/' + vin + "/v/#";
        connectivityTopicFilter = topicBase + '/' + vin + "/connectivity";
    }

    @Override
    public Closeable subscribeToMetrics(Consumer<? super TelemetryField> listener) {
        logger.debug("subscribing to {}", metricsTopicFilter);
        return mqtt.subscribe(metricsTopicFilter, 1, (topic, data) -> {
            //TODO:commerce this logs sensitive data (vin in the topic)
            logger.debug("received metric: topic={}, data={}", topic, data);
            var idx = topic.lastIndexOf('/');
            if (idx < 0 || idx == topic.length() - 1) {
                logger.warn("Unexpected topic name: {}", topic); //TODO:commerce this exposes VIN to the log file/alert
                return;
            }
            String fieldName = topic.substring(idx + 1);
            TelemetryField field;
            try {
                field = TelemetryFieldDecoder.decode(fieldName, data);
            } catch (RuntimeException e) {
                //TODO:commerce alert/antispam, also mask VIN
                logger.warn("[{}] failed decoding field data {}={}", vin, fieldName, data, e);
                return;
            }
            if (field == null) {
                logger.debug("Unsupported field: {}", fieldName);
                return;
            }
            listener.accept(field);
        });
    }

    @Override
    public Closeable subscribeToConnectivity(Consumer<? super TelemetryConnectivityEvent> listener) {
        return mqtt.subscribe(connectivityTopicFilter, 1, (topic, data) -> {
            //TODO:commerce this logs sensitive data
            logger.debug("received {}={}", topic, data);
            try {
                listener.accept(Json.parse(data, TelemetryConnectivityEvent.class));
            } catch (RuntimeException e) {
                //TODO:commerce alert/antispam, also mask VIN
                logger.warn("[{}] failed decoding connectivity data {}", vin, data, e);
            }
        });
    }

    @Override
    public Closeable subscribeToBrokerConnectionStatus(Consumer<BrokerConnectionStatus> listener) {
        return mqtt.subscribeToConnectionStatus(status -> listener.accept(switch (status) {
            case Mqtt.Connected _ -> CONNECTED;
            case Mqtt.Disconnected _ -> DISCONNECTED;
        }));
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface TopicBase {
    }
}
