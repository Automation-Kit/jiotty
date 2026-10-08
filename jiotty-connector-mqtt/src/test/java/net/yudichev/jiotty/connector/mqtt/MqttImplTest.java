package net.yudichev.jiotty.connector.mqtt;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static net.yudichev.jiotty.common.lang.backoff.ExponentialBackOff.DEFAULT_MAX_ELAPSED_TIME_MILLIS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Unit tests for [MqttImpl]'s re-subscription retries after a reconnect, driven by a [ProgrammableClock] against a client double whose subscribe can be
/// made to fail. [MqttImplIntegrationTest] covers the broker-facing behaviour.
@ExtendWith(MockitoExtension.class)
class MqttImplTest {
    /// Covers the re-subscription backoff's limit plus its longest randomised retry interval.
    private static final Duration PAST_THE_RETRY_LIMIT = Duration.ofMillis(DEFAULT_MAX_ELAPSED_TIME_MILLIS).plusMinutes(1);

    @Mock
    private IMqttAsyncClient client;
    @Mock
    private IMqttToken disconnectToken;
    @Mock
    private TaskFailureReporter taskFailureReporter;
    @Captor
    private ArgumentCaptor<MqttCallbackExtended> callbackCaptor;
    private ProgrammableClock clock;
    private MqttImpl mqtt;
    private int subscribeAttempts;
    private boolean failSubscriptions;

    @BeforeEach
    void setUp() throws MqttException {
        clock = new ProgrammableClock();
        when(client.connect(any(), any(), any())).thenAnswer(invocation -> {
            invocation.<IMqttActionListener>getArgument(2).onSuccess(null);
            return null;
        });
        when(client.subscribe(anyString(), anyInt(), any(IMqttMessageListener.class))).thenAnswer(_ -> {
            subscribeAttempts++;
            if (failSubscriptions) {
                throw new MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
            }
            return null;
        });
        when(client.disconnect()).thenReturn(disconnectToken);

        mqtt = new MqttImpl(client, clock, (_, _, _) -> _ -> {}, _ -> {}, taskFailureReporter, clock, 0.0);
        mqtt.start();
        clock.tick();
        verify(client).setCallback(callbackCaptor.capture());
        mqtt.subscribe("home/sensor", (_, _) -> {});
        clock.tick();
    }

    @AfterEach
    void tearDown() {
        mqtt.stop();
    }

    @Test
    void aReSubscriptionThatKeepsFailingStopsRetryingPastTheRetryLimitAndReportsIt() {
        failSubscriptions = true;
        reconnect();
        clock.advanceTimeAndTick(PAST_THE_RETRY_LIMIT);
        int attemptsWhenGivenUp = subscribeAttempts;

        clock.advanceTimeAndTick(Duration.ofHours(1));

        assertThat(attemptsWhenGivenUp).isGreaterThan(2);
        assertThat(subscribeAttempts).isEqualTo(attemptsWhenGivenUp);
        verify(taskFailureReporter).onTaskException(contains("restoring MQTT subscriptions"), any());
    }

    @Test
    void aReconnectAfterTheReSubscriptionGaveUpRetriesItAfresh() {
        failSubscriptions = true;
        reconnect();
        clock.advanceTimeAndTick(PAST_THE_RETRY_LIMIT);
        int attemptsBeforeReconnect = subscribeAttempts;

        reconnect();
        clock.advanceTimeAndTick(Duration.ofSeconds(1));

        assertThat(subscribeAttempts - attemptsBeforeReconnect).isGreaterThan(1);
    }

    private void reconnect() {
        callbackCaptor.getValue().connectComplete(true, "tcp://broker.example.com:1883");
        clock.tick();
    }
}
