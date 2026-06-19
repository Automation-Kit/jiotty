package net.yudichev.jiotty.connector.tesla.fleet;

import com.google.inject.BindingAnnotation;
import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.security.LogRedaction;
import net.yudichev.jiotty.connector.mqtt.Mqtt;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/// Feeds data from the MQTT dispatcher of the [Tesla Fleet Telemetry Server](https://github.com/teslamotors/fleet-telemetry)
public final class MqttTeslaTelemetry implements TeslaTelemetry {
    private static final Logger logger = LogManager.getLogger(MqttTeslaTelemetry.class);

    private final Mqtt mqtt;
    private final String vin;
    private final String redactedVin;
    private final String metricsTopicFilter;
    private final String connectivityTopicFilter;

    @Inject
    public MqttTeslaTelemetry(@Dependency Mqtt mqtt,
                              @TopicBase String topicBase,
                              @Assisted("vin") String vin) {
        this.mqtt = checkNotNull(mqtt);
        this.vin = checkNotNull(vin);
        redactedVin = LogRedaction.redact(vin);
        metricsTopicFilter = topicBase + '/' + vin + "/v/#";
        connectivityTopicFilter = topicBase + '/' + vin + "/connectivity";
    }

    @Override
    public Closeable subscribeToMetrics(Consumer<? super TelemetryResult<TelemetryField>> listener) {
        if (logger.isDebugEnabled()) {
            logger.debug("subscribing to {}", redactedTopic(metricsTopicFilter));
        }
        return mqtt.subscribe(metricsTopicFilter, 1, (topic, data) -> {
            if (logger.isDebugEnabled()) {
                logger.debug("received metric: topic={}, data={}", redactedTopic(topic), data);
            }
            var idx = topic.lastIndexOf('/');
            if (idx < 0 || idx == topic.length() - 1) {
                listener.accept(new TelemetryResult.Error<>("Unexpected telemetry topic structure for " + redactedVin, null));
                return;
            }
            String fieldName = topic.substring(idx + 1);
            TelemetryField field;
            try {
                field = TelemetryFieldDecoder.decode(fieldName, data);
            } catch (RuntimeException e) {
                listener.accept(new TelemetryResult.Error<>("Failed to decode telemetry field '" + fieldName + "' for " + redactedVin, e));
                return;
            }
            if (field == null) {
                logger.debug("Unsupported field: {}", fieldName);
                return;
            }
            listener.accept(new TelemetryResult.Success<>(field));
        });
    }

    @Override
    public Closeable subscribeToConnectivity(Consumer<? super TelemetryResult<TelemetryConnectivityEvent>> listener) {
        return mqtt.subscribe(connectivityTopicFilter, 1, (topic, data) -> {
            if (logger.isDebugEnabled()) {
                logger.debug("received connectivity event: topic={}, data={}", redactedTopic(topic), data);
            }
            TelemetryConnectivityEvent event;
            try {
                event = Json.parse(data, TelemetryConnectivityEvent.class);
            } catch (RuntimeException e) {
                listener.accept(new TelemetryResult.Error<>("Failed to decode connectivity event for " + redactedVin, e));
                return;
            }
            listener.accept(new TelemetryResult.Success<>(event));
        });
    }

    private String redactedTopic(String topic) {
        return topic.replace(vin, redactedVin);
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
