package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.time.Instant;
import java.util.Optional;

@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseStandardUnitRate {
    @JsonProperty("value_exc_vat")
    double valueExcVat();

    @JsonProperty("value_inc_vat")
    double valueIncVat();

    @JsonProperty("valid_from")
    Instant validFrom();

    /// The instant the rate ends, exclusive; empty when Octopus sends `"valid_to": null`, which it does for the rate in force on a variable tariff, whose end
    /// date is not set yet. A half-hourly (Agile) rate always has one.
    @JsonProperty("valid_to")
    Optional<Instant> validTo();
}
