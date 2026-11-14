package net.yudichev.jiotty.connector.miele;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseDeviceState extends MieleEvent {
    @JsonProperty("ProgramID")
    StateValue program();

    StateValue status();

    /// @return the hours and minutes of the remaining time, or an empty list when the appliance reports none
    @JsonProperty("remainingTime")
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    List<Integer> remainingTimeParts();

    @JsonIgnore
    @Value.Derived
    default Optional<Duration> remainingTime() {
        List<Integer> parts = remainingTimeParts();
        return parts.size() == 2 ? Optional.of(Duration.ofHours(parts.getFirst()).plusMinutes(parts.get(1))) : Optional.empty();
    }

    @JsonProperty("remoteEnable")
    RemoteControlStatus remoteControlStatus();
}
