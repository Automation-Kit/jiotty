package net.yudichev.jiotty.connector.miele;

import net.yudichev.jiotty.common.lang.Json;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static net.yudichev.jiotty.connector.miele.MieleTestResources.NOT_CONNECTED_DEVICE;
import static net.yudichev.jiotty.connector.miele.MieleTestResources.RUNNING_DEVICE;
import static net.yudichev.jiotty.connector.miele.MieleTestResources.parse;
import static net.yudichev.jiotty.connector.miele.MieleTestResources.read;
import static org.assertj.core.api.Assertions.assertThat;

class MieleDeviceTest {
    @Test
    void deserialises1() {
        testDeserialisation(RUNNING_DEVICE, state -> {
            assertThat(state.program()).isEqualTo(StateValue.builder()
                                                            .setId(7)
                                                            .setName("Auto")
                                                            .build());
            assertThat(state.remainingTime()).hasValue(Duration.ofHours(3).plusMinutes(11));
            assertThat(state.status()).isEqualTo(StateValue.builder()
                                                           .setId(5)
                                                           .setName("In use")
                                                           .build());
            assertThat(state.remoteControlStatus()).isEqualTo(RemoteControlStatus.builder()
                                                                                 .setMobileStart(true)
                                                                                 .setFullRemoteControl(true)
                                                                                 .build());
        });
    }

    @Test
    void deserialises2() {
        testDeserialisation(NOT_CONNECTED_DEVICE, state -> {
            assertThat(state.program()).isEqualTo(StateValue.builder()
                                                            .setId(0)
                                                            .setName("")
                                                            .build());
            assertThat(state.remainingTime()).isEmpty();
            assertThat(state.status()).isEqualTo(StateValue.builder()
                                                           .setId(255)
                                                           .setName("Not connected")
                                                           .build());
            assertThat(state.remoteControlStatus()).isEqualTo(RemoteControlStatus.builder()
                                                                                 .setMobileStart(false)
                                                                                 .setFullRemoteControl(false)
                                                                                 .build());
        });
    }

    @Test
    void devicesParsedFromSamePayloadAreEqual() {
        assertThat(parse(RUNNING_DEVICE, MieleDevice.class)).isEqualTo(parse(RUNNING_DEVICE, MieleDevice.class));
    }

    @Test
    void deserialisesNullRemainingTimeAsNone() {
        String json = read(NOT_CONNECTED_DEVICE).replace("\"remainingTime\": []", "\"remainingTime\": null");

        MieleDevice mieleDevice = Json.parse(json, MieleDevice.class);

        assertThat(mieleDevice.state().remainingTimeParts()).isEmpty();
        assertThat(mieleDevice.state().remainingTime()).isEmpty();
    }

    private static void testDeserialisation(String name, ThrowingConsumer<DeviceState> deviceStateThrowingConsumer) {
        assertThat(parse(name, MieleDevice.class).state()).satisfies(deviceStateThrowingConsumer);
    }
}
