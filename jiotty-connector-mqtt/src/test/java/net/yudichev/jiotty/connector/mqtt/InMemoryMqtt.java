package net.yudichev.jiotty.connector.mqtt;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/// Basic in-memory [Mqtt] implementation for tests.
public final class InMemoryMqtt implements Mqtt {
    private final Map<String, String> dataByTopic = new HashMap<>();
    private final Map<Pattern, Listeners<TopicAndData>> listenersByFilter = new HashMap<>();
    private final Object lock = new Object();
    private final Listeners<ConnectionStatus> connectionStatusListeners = new Listeners<>();
    private ConnectionStatus connectionStatus = new Connected(false);

    @Override
    public Closeable subscribe(String topicFilter, int qos, BiConsumer<String, String> dataCallback) {
        List<TopicAndData> image = null;
        Closeable listenerRegistration;
        var pattern = Pattern.compile(topicFilter.replace("#", ".*"));
        synchronized (lock) {
            listenerRegistration = listenersByFilter.computeIfAbsent(pattern, _ -> new Listeners<>())
                                                    .addListener(topicAndData -> topicAndData.notify(dataCallback));
            if (qos > 0) {
                // send image
                image = new ArrayList<>();
                for (Map.Entry<String, String> entry : dataByTopic.entrySet()) {
                    String topic = entry.getKey();
                    String data = entry.getValue();
                    if (pattern.matcher(topic).matches()) {
                        image.add(new TopicAndData(topic, data));
                    }
                }
            }
        }
        if (image != null) {
            image.forEach(t -> t.notify(dataCallback));
        }
        return Closeable.idempotent(() -> {
            synchronized (lock) {
                listenerRegistration.close();
                Listeners<TopicAndData> topicAndDataListeners = listenersByFilter.get(pattern);
                if (topicAndDataListeners.isEmpty()) {
                    listenersByFilter.remove(pattern);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> publish(String topic, String message) {
        List<Listeners<TopicAndData>> listeners = new ArrayList<>();
        synchronized (lock) {
            dataByTopic.put(topic, message);
            listenersByFilter.forEach((pattern, topicAndDataListeners) -> {
                if (pattern.matcher(topic).matches()) {
                    listeners.add(topicAndDataListeners);
                }
            });
        }
        var topicAndData = new TopicAndData(topic, message);
        listeners.forEach((topicAndDataListeners) -> topicAndDataListeners.notify(topicAndData));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Closeable subscribeToConnectionStatus(Consumer<ConnectionStatus> listener) {
        synchronized (lock) {
            listener.accept(connectionStatus);
        }
        return connectionStatusListeners.addListener(listener);
    }

    public int subscriberCount() {
        return listenersByFilter.values().stream().map(Listeners::size).mapToInt(Integer::intValue).sum();
    }

    public void setConnectionStatus(ConnectionStatus connectionStatus) {
        synchronized (lock) {
            this.connectionStatus = connectionStatus;
            connectionStatusListeners.notify(connectionStatus);
        }
    }

    private record TopicAndData(String topic, String data) {
        public void notify(BiConsumer<String, String> dataCallback) {
            dataCallback.accept(topic, data);
        }
    }
}
