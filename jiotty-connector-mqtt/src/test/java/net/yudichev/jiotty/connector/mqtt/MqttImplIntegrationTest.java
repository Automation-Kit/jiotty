package net.yudichev.jiotty.connector.mqtt;

import net.yudichev.jiotty.common.async.ExecutorFactoryImpl;
import net.yudichev.jiotty.common.lang.Closeable;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// Integration tests for [MqttImpl] against a real in-JVM MQTT broker ([EmbeddedMqttBrokerExtension]). Unlike a mock-based test, these exercise genuine broker
/// semantics — retained-message redelivery on subscribe, per-subscription delivery, reconnect resubscription — which is where the subtle subscription-sharing
/// behaviour actually lives.
class MqttImplIntegrationTest {
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final long PAHO_OP_TIMEOUT_MILLIS = 10_000;

    @RegisterExtension
    static final EmbeddedMqttBrokerExtension broker = new EmbeddedMqttBrokerExtension();

    private static final ExecutorFactoryImpl executorFactory = new ExecutorFactoryImpl();

    private final List<MqttImpl> startedClients = new ArrayList<>();
    private final List<Closeable> subscriptions = new ArrayList<>();

    @AfterEach
    void tearDown() {
        subscriptions.forEach(Closeable::close);
        startedClients.forEach(MqttImpl::stop);
    }

    /// Every local subscriber to a filter — not just the first — must receive the broker's retained message for that filter. In production a single
    /// car-server process shares one [MqttImpl] connection across all users, and each user's car-control subscribes to the same retained telemetry-CA topic;
    /// the second and later subscribers must see the CA too, otherwise their vehicle telemetry never starts.
    @Test
    void retainedMessageReachesEverySubscriberToTheSameFilterNotJustTheFirst() throws Exception {
        // The infra publisher (mosquitto_pub -r) leaves a retained message on the topic before any app subscribes.
        String topic = uniqueTopic();
        publishRetained(topic, "CA-PAYLOAD");

        MqttImpl client = newConnectedClient();

        CompletableFuture<String> firstSubscriber = subscribeAwaitingPayload(client, topic);
        assertThat(firstSubscriber).succeedsWithin(AWAIT_TIMEOUT).isEqualTo("CA-PAYLOAD");

        CompletableFuture<String> secondSubscriber = subscribeAwaitingPayload(client, topic);
        assertThat(secondSubscriber).succeedsWithin(AWAIT_TIMEOUT).isEqualTo("CA-PAYLOAD");
    }

    /// Two subscribers to the same filter both receive its messages; closing one subscription stops only that one and leaves the other receiving.
    @Test
    void unsubscribingOneSubscriberStopsOnlyThatSubscription() throws Exception {
        String topic = uniqueTopic();
        MqttImpl client = newConnectedClient();

        var subscriberAPayloads = new CopyOnWriteArrayList<String>();
        Closeable subscriberA = client.subscribe(topic, (_, payload) -> subscriberAPayloads.add(payload));
        subscriptions.add(subscriberA);

        var subscriberBPayloads = new CopyOnWriteArrayList<String>();
        var subscriberBFirst = new CompletableFuture<String>();
        var subscriberBSecond = new CompletableFuture<String>();
        subscriptions.add(client.subscribe(topic, (_, payload) -> {
            subscriberBPayloads.add(payload);
            (subscriberBPayloads.size() == 1 ? subscriberBFirst : subscriberBSecond).complete(payload);
        }));

        // both subscribers receive the first retained value
        publishRetained(topic, "v1");
        assertThat(subscriberBFirst).succeedsWithin(AWAIT_TIMEOUT).isEqualTo("v1");

        // subscriber A leaves; subscriber B stays subscribed
        subscriberA.close();

        // a second retained value reaches only the still-subscribed B
        publishRetained(topic, "v2");
        assertThat(subscriberBSecond).succeedsWithin(AWAIT_TIMEOUT).isEqualTo("v2");
        assertThat(subscriberAPayloads).containsExactly("v1");
    }

    /// After the broker bounces and the client auto-reconnects, [MqttImpl] restores its subscriptions, so a message published after the reconnect is still
    /// delivered.
    @Test
    void restoresSubscriptionAfterBrokerReconnect() throws Exception {
        String topic = uniqueTopic();
        MqttImpl client = newConnectedClient();

        var reconnected = new CompletableFuture<Void>();
        subscriptions.add(client.subscribeToConnectionStatus(status -> {
            if (status instanceof Mqtt.Connected(boolean reconnect) && reconnect) {
                reconnected.complete(null);
            }
        }));
        CompletableFuture<String> afterReconnect = subscribeAwaitingPayload(client, topic);

        broker.restart();
        assertThat(reconnected).succeedsWithin(AWAIT_TIMEOUT);

        publishRetained(topic, "after-reconnect");
        assertThat(afterReconnect).succeedsWithin(AWAIT_TIMEOUT).isEqualTo("after-reconnect");
    }

    /// Two subscribers to the same filter must BOTH keep receiving after a reconnect: the restore path registers one fan-out listener per filter, so neither
    /// subscriber is dropped when the broker bounces.
    @Test
    void afterReconnectEverySubscriberToTheSameFilterStillReceives() throws Exception {
        String topic = uniqueTopic();
        MqttImpl client = newConnectedClient();

        var reconnected = new CompletableFuture<Void>();
        subscriptions.add(client.subscribeToConnectionStatus(status -> {
            if (status instanceof Mqtt.Connected(boolean reconnect) && reconnect) {
                reconnected.complete(null);
            }
        }));
        CompletableFuture<String> subscriberA = subscribeAwaitingPayload(client, topic);
        CompletableFuture<String> subscriberB = subscribeAwaitingPayload(client, topic);

        broker.restart();
        assertThat(reconnected).succeedsWithin(AWAIT_TIMEOUT);

        publishRetained(topic, "after-reconnect");
        assertThat(subscriberA).succeedsWithin(AWAIT_TIMEOUT).isEqualTo("after-reconnect");
        assertThat(subscriberB).succeedsWithin(AWAIT_TIMEOUT).isEqualTo("after-reconnect");
    }

    private MqttImpl newConnectedClient() throws Exception {
        IMqttAsyncClient pahoClient = new MqttAsyncClient(broker.serverUri(), "client-" + UUID.randomUUID(), new MemoryPersistence());
        MqttImpl client = new MqttImpl(pahoClient, executorFactory, (_, _, _) -> _ -> {}, _ -> {}, System::nanoTime, 0.0);
        client.start();
        startedClients.add(client);
        var connected = new CompletableFuture<Void>();
        subscriptions.add(client.subscribeToConnectionStatus(status -> {
            if (status instanceof Mqtt.Connected) {
                connected.complete(null);
            }
        }));
        assertThat(connected).succeedsWithin(AWAIT_TIMEOUT);
        return client;
    }

    private CompletableFuture<String> subscribeAwaitingPayload(MqttImpl client, String topicFilter) {
        var received = new CompletableFuture<String>();
        subscriptions.add(client.subscribe(topicFilter, (_, payload) -> received.complete(payload)));
        return received;
    }

    private static void publishRetained(String topic, String payload) throws Exception {
        IMqttAsyncClient publisher = new MqttAsyncClient(broker.serverUri(), "retain-pub-" + UUID.randomUUID(), new MemoryPersistence());
        publisher.connect().waitForCompletion(PAHO_OP_TIMEOUT_MILLIS);
        var message = new MqttMessage(payload.getBytes(UTF_8));
        message.setQos(1);
        message.setRetained(true);
        publisher.publish(topic, message).waitForCompletion(PAHO_OP_TIMEOUT_MILLIS);
        publisher.disconnect().waitForCompletion(PAHO_OP_TIMEOUT_MILLIS);
        publisher.close();
    }

    private static String uniqueTopic() {
        return "it/" + UUID.randomUUID();
    }
}
