package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.time.Instant;

/// One row from the `/electricity-meter-points/{mpan}/meters/{serial}/consumption/` endpoint. Octopus returns rows in half-hour slots by default.
@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseConsumptionRow {
    @JsonProperty("interval_start")
    Instant intervalStart();

    @JsonProperty("interval_end")
    Instant intervalEnd();

    /// kWh consumed over the interval.
    double consumption();
}
