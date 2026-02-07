package net.yudichev.jiotty.connector.mqtt;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.Scheduler;
import net.yudichev.jiotty.common.lang.Closeable;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqttImplTest {
    private static final String TOPIC_FILTER = "/topic/+";
    private final List<Mqtt.ConnectionStatus> receivedConnectionStatuses = new ArrayList<>();
    @Mock
    private IMqttAsyncClient client;
    @Mock
    private BiConsumer<String, String> dataCallback;
    @Captor
    private ArgumentCaptor<IMqttMessageListener> messageListenerArgumentCaptor;
    @Captor
    private ArgumentCaptor<IMqttActionListener> actionListenerCaptor;
    private MqttImpl mqtt;
    private MqttCallbackExtended mqttCallback;
    private ProgrammableClock clock;

    @BeforeEach
    void setUp() throws MqttException {
        clock = new ProgrammableClock().withMdc();

        mqtt = new MqttImpl(client, clock, (_, _, _) -> _ -> {}, _ -> {}, clock, 0) {
            @Override
            void scheduleReconnect(Scheduler scheduler, Long delayMillis, Runnable runnable) {
                scheduler.schedule(Duration.ofMillis(delayMillis), runnable);
            }

            @Override
            void waitForConnectFutureAndThen(CompletableFuture<Void> connectFuture, Runnable whenDone) {
                connectFuture.thenRun(whenDone);
            }
        };
        mqtt.start();
        clock.tick();

        ArgumentCaptor<MqttCallbackExtended> callbackCaptor = ArgumentCaptor.forClass(MqttCallbackExtended.class);
        verify(client).setCallback(callbackCaptor.capture());
        verify(client).connect(any(), eq(null), actionListenerCaptor.capture());
        mqttCallback = callbackCaptor.getValue();

        // testing re-connect
        IMqttActionListener actionListener = actionListenerCaptor.getValue();
        actionListener.onFailure(null, new RuntimeException("failure1"));

        clock.advanceTimeAndTick(Duration.ofSeconds(1));
        verify(client, times(2)).connect(any(), eq(null), actionListenerCaptor.capture());
        actionListener = actionListenerCaptor.getValue();
        actionListener.onSuccess(null);

        reset(client);
        clock.advanceTimeAndTick(Duration.ofDays(1));
        verify(client, never()).connect(any(), any(), any());
    }

    @Test
    void resubscribesOnReconnect() throws MqttException {
        IMqttMessageListener messageListener = doSubscribe();
        var subscription = mqtt.subscribeToConnectionStatus(receivedConnectionStatuses::add);
        clock.tick();
        assertThat(receivedConnectionStatuses).singleElement().isEqualTo(new Mqtt.Connected(false));
        receivedConnectionStatuses.clear();

        var exception = new RuntimeException("oops");
        mqttCallback.connectionLost(exception);
        clock.tick();
        assertThat(receivedConnectionStatuses).singleElement().isEqualTo(new Mqtt.Disconnected(exception));
        receivedConnectionStatuses.clear();

        mqttCallback.connectComplete(true, "serverUri");
        clock.tick();
        assertThat(receivedConnectionStatuses).singleElement().isEqualTo(new Mqtt.Connected(true));
        receivedConnectionStatuses.clear();

        subscription.close();

        verify(client).subscribe(TOPIC_FILTER, 2, messageListener);
    }

    @Test
    void deliversLastMatchingMessageOnSubscribe() throws Exception {
        mqttCallback.messageArrived("/topic/a", mqttMessage());
        mqttCallback.messageArrived("/topic2/b", mqttMessage());

        doSubscribe();

        verify(dataCallback).accept("/topic/a", "msg");
    }

    @Test
    void multipleSubscriptionsToSameTopic(@Mock BiConsumer<String, String> dataCallback2) throws Exception {
        when(client.isConnected()).thenReturn(true);

        mqttCallback.connectComplete(false, "serverUrl");
        Closeable sub1 = mqtt.subscribe(TOPIC_FILTER, dataCallback);
        clock.tick();

        verify(client).subscribe(eq(TOPIC_FILTER), eq(2), messageListenerArgumentCaptor.capture());
        IMqttMessageListener mqttListener = messageListenerArgumentCaptor.getValue();

        Closeable sub2 = mqtt.subscribe(TOPIC_FILTER, dataCallback2);
        clock.tick();
        verify(client, times(1)).subscribe(eq(TOPIC_FILTER), eq(2), any());

        mqttListener.messageArrived("/topic/a", mqttMessage("msg"));
        clock.tick();
        verify(dataCallback).accept("/topic/a", "msg");
        verify(dataCallback2).accept("/topic/a", "msg");

        sub1.close();
        clock.tick();
        verify(client, never()).unsubscribe(TOPIC_FILTER);

        mqttListener.messageArrived("/topic/a", mqttMessage("msg2"));
        clock.tick();
        verify(dataCallback, never()).accept("/topic/a", "msg2");
        verify(dataCallback2).accept("/topic/a", "msg2");

        sub2.close();
        clock.tick();
        verify(client).unsubscribe(TOPIC_FILTER);
    }

    private IMqttMessageListener doSubscribe() throws MqttException {
        mqttCallback.connectComplete(false, "serverUrl");
        mqtt.subscribe(TOPIC_FILTER, dataCallback);
        clock.tick();

        verify(client).subscribe(eq(TOPIC_FILTER), eq(2), messageListenerArgumentCaptor.capture());
        return messageListenerArgumentCaptor.getValue();
    }

    private static MqttMessage mqttMessage() {
        return mqttMessage("msg");
    }

    private static MqttMessage mqttMessage(String msg) {
        return new MqttMessage(msg.getBytes(UTF_8));
    }
}