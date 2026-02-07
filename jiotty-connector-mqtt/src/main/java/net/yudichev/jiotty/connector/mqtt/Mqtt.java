package net.yudichev.jiotty.connector.mqtt;

import net.yudichev.jiotty.common.lang.Closeable;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface Mqtt {
    Closeable subscribe(String topicFilter, int qos, BiConsumer<String, String> dataCallback);

    default Closeable subscribe(String topicFilter, BiConsumer<String, String> dataCallback) {
        return subscribe(topicFilter, 2, dataCallback);
    }

    CompletableFuture<Void> publish(String topic, String message);

    Closeable subscribeToConnectionStatus(Consumer<ConnectionStatus> listener);

    sealed interface ConnectionStatus permits Connected, Disconnected {
    }

    record Connected(boolean reconnect) implements ConnectionStatus {}

    record Disconnected(Throwable reason) implements ConnectionStatus {}
}
