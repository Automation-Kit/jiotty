package net.yudichev.jiotty.connector.mqtt;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/// Basic in-memory [Mqtt] implementation for tests.
public final class InMemoryMqtt implements Mqtt {
    private final Map<String, String> dataByTopic = new HashMap<>();
    private final Map<Pattern, Listeners<Message>> listenersByFilter = new HashMap<>();
    private final Object lock = new Object();
    private final Listeners<ConnectionStatus> connectionStatusListeners = new Listeners<>();
    private ConnectionStatus connectionStatus = new Connected(false);

    @Override
    public Closeable subscribeToMessages(String topicFilter, int qos, Consumer<Message> messageCallback) {
        List<Message> image = new ArrayList<>();
        Closeable listenerRegistration;
        var pattern = Pattern.compile(topicFilter.replace("#", ".*"));
        synchronized (lock) {
            listenerRegistration = listenersByFilter.computeIfAbsent(pattern, _ -> new Listeners<>()).addListener(messageCallback);
            // send image
            dataByTopic.forEach((topic, data) -> {
                if (pattern.matcher(topic).matches()) {
                    image.add(new Message(topic, data, true));
                }
            });
        }
        image.forEach(messageCallback);
        return Closeable.idempotent(() -> {
            synchronized (lock) {
                listenerRegistration.close();
                Listeners<Message> messageListeners = listenersByFilter.get(pattern);
                if (messageListeners.isEmpty()) {
                    listenersByFilter.remove(pattern);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> publish(String topic, String message) {
        List<Listeners<Message>> listeners = new ArrayList<>();
        synchronized (lock) {
            dataByTopic.put(topic, message);
            listenersByFilter.forEach((pattern, messageListeners) -> {
                if (pattern.matcher(topic).matches()) {
                    listeners.add(messageListeners);
                }
            });
        }
        var publishedMessage = new Message(topic, message, false);
        listeners.forEach((messageListeners) -> messageListeners.notify(publishedMessage));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> clearRetained(String topic) {
        synchronized (lock) {
            dataByTopic.remove(topic);
        }
        return CompletableFuture.completedFuture(null);
    }

    /// @return the message held for `topic`, as a later subscriber would receive it first
    public Optional<String> retainedMessage(String topic) {
        synchronized (lock) {
            return Optional.ofNullable(dataByTopic.get(topic));
        }
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
}
