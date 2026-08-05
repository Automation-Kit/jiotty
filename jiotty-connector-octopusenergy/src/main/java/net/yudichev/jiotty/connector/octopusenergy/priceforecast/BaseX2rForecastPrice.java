package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.time.Instant;

/// One half-hour slot of an api.x2r.uk forecast.
@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseX2rForecastPrice {
    /// Start of the half-hour slot.
    @JsonProperty("date")
    Instant dateTime();

    /// Predicted price in p/kWh including VAT.
    @JsonProperty("price")
    double predictedPrice();
}
