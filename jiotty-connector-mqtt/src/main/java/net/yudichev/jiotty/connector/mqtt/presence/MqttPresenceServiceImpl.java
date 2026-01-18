package net.yudichev.jiotty.connector.mqtt.presence;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.connector.mqtt.Mqtt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.connector.mqtt.presence.MqttPresenceServiceModule.Dependency;
import static net.yudichev.jiotty.connector.mqtt.presence.MqttPresenceServiceModule.Name;
import static net.yudichev.jiotty.connector.mqtt.presence.MqttPresenceServiceModule.Topic;

final class MqttPresenceServiceImpl extends BaseLifecycleComponent implements MqttPresenceService {
    private static final Logger logger = LoggerFactory.getLogger(MqttPresenceServiceImpl.class);
    private static final Duration PRESENCE_DETECTION_TIMEOUT = Duration.ofMinutes(3);

    private final Mqtt mqtt;
    private final String name;
    private final String topic;
    private final ExecutorFactory executorFactory;
    private final Listeners<Boolean> listeners = new Listeners<>();
    private SchedulingExecutor executor;
    private Closeable mqttSubscription;
    @Nullable
    private Closeable timeoutSchedule;
    private Boolean present;

    @Inject
    public MqttPresenceServiceImpl(@Dependency Mqtt mqtt,
                                   @Name String name,
                                   @Topic String topic,
                                   ExecutorFactory executorFactory) {
        this.mqtt = checkNotNull(mqtt);
        this.name = checkNotNull(name);
        this.topic = checkNotNull(topic);
        this.executorFactory = checkNotNull(executorFactory);
    }

    @Override
    public Closeable addListener(Consumer<Boolean> listener) {
        return whenStartedAndNotLifecycling(() -> listeners.addListener(executor, () -> Optional.ofNullable(present), listener));
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor(name);
        mqttSubscription = mqtt.subscribe(topic,
                                          (topic, data) -> executor.execute(() -> onMsg(topic, data)));
        scheduleTimeout();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, mqttSubscription, executor);
    }

    private void onMsg(String topic, String data) {
        logger.trace("Received message {}->{}", topic, data);
        scheduleTimeout();
        onPresenceChange(Boolean.TRUE);
    }

    private void scheduleTimeout() {
        closeIfNotNull(timeoutSchedule);
        timeoutSchedule = executor.schedule(PRESENCE_DETECTION_TIMEOUT, this::onTimeout);
    }

    private void onTimeout() {
        logger.debug("Presence timeout");
        timeoutSchedule = null;
        onPresenceChange(Boolean.FALSE);
    }

    private void onPresenceChange(Boolean newPresence) {
        if (!Objects.equals(present, newPresence)) {
            logger.debug("Presence {} -> {}", present, newPresence);
            present = newPresence;
            listeners.notify(newPresence);
        }
    }
}
