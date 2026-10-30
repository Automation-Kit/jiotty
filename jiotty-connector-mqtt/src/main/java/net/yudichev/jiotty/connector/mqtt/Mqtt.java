package net.yudichev.jiotty.connector.mqtt;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;

public interface Mqtt {
    /// Delivers every message on a topic matching `topicFilter` to `messageCallback`, starting with the latest message already known for each matching topic,
    /// which is marked [Message#replayed()] like a copy the broker retained.
    ///
    /// @param messageCallback receives each message; may run on any thread, the subscribing or publishing one included, so a subscriber that owns state must
    ///                        hand each message to its own executor
    Closeable subscribeToMessages(String topicFilter, int qos, Consumer<Message> messageCallback);

    default Closeable subscribe(String topicFilter, int qos, BiConsumer<String, String> dataCallback) {
        return subscribeToMessages(topicFilter, qos, message -> dataCallback.accept(message.topic(), message.data()));
    }

    default Closeable subscribe(String topicFilter, BiConsumer<String, String> dataCallback) {
        return subscribe(topicFilter, 2, dataCallback);
    }

    /// @return completes once the broker has acknowledged the message
    CompletableFuture<Void> publish(String topic, String message);

    /// Removes the message the broker retains on `topic`, so a later subscriber receives nothing for it until something new is published.
    ///
    /// @return completes once the broker has acknowledged the removal
    CompletableFuture<Void> clearRetained(String topic);

    Closeable subscribeToConnectionStatus(Consumer<ConnectionStatus> listener);

    sealed interface ConnectionStatus permits Connected, Disconnected {
    }

    record Connected(boolean reconnect) implements ConnectionStatus {}

    record Disconnected(Throwable reason) implements ConnectionStatus {}

    /// A message delivered to a subscriber.
    ///
    /// @param topic    the topic the message was published on
    /// @param data     the payload, decoded as UTF-8
    /// @param replayed `true` for a copy of a message published before the subscription took effect, such as the broker's retained message
    record Message(String topic, String data, boolean replayed) {
        public Message {
            checkNotNull(topic);
            checkNotNull(data);
        }
    }
}
