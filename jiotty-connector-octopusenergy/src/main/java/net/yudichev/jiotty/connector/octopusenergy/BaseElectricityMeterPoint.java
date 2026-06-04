package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.List;

@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseElectricityMeterPoint {
    @JsonProperty("agreements")
    List<Tariff> tariffs();

    @Value.Redacted
    String mpan();

    @JsonProperty("meters")
    List<ElectricityMeter> meters();

    /// Whether this is an *export* meter point (energy the property sends back to the grid, e.g. solar / SEG) rather than an import (consumption) point.
    /// Defaults to `false` when the account payload omits the field, so an absent flag reads as a consumption (import) point.
    @JsonProperty("is_export")
    @Value.Default
    default boolean isExport() {
        return false;
    }
}
