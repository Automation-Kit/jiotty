package net.yudichev.jiotty.connector.sonyprojector;

import com.google.common.collect.ImmutableMap;

import java.util.Arrays;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

public enum SonyProjectorPowerState {
    ON("on"),
    STANDBY("standby"),
    STARTUP("startup"),
    COOLING1("cooling1"),
    COOLING2("cooling2"),
    SAVING_COOLING1("saving_cooling1"),
    SAVING_COOLING2("saving_cooling2"),
    SAVING_STANDBY("saving_standby"),
    UNKNOWN("unknown");

    private static final ImmutableMap<String, SonyProjectorPowerState> PROTOCOL_VALUES = Arrays.stream(values())
                                                                                               .filter(state -> state != UNKNOWN)
                                                                                               .collect(toImmutableMap(SonyProjectorPowerState::protocolValue,
                                                                                                                       state -> state));

    private final String protocolValue;

    SonyProjectorPowerState(String protocolValue) {
        this.protocolValue = protocolValue;
    }

    public String protocolValue() {
        return protocolValue;
    }

    public static SonyProjectorPowerState fromProtocolValue(String value) {
        SonyProjectorPowerState state = PROTOCOL_VALUES.get(value);
        return state == null ? UNKNOWN : state;
    }
}
