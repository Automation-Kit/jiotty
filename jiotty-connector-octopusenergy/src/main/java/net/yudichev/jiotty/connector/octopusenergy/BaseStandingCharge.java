package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.time.Instant;
import java.util.Optional;

/// One standing-charge row from `/products/{p}/electricity-tariffs/{t}/standing-charges/`. The currently-active charge for a tariff has `validTo` empty (the
/// Octopus API returns `"valid_to": null`); historical charges have it populated.
@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseStandingCharge {
    @JsonProperty("value_exc_vat")
    double valueExcVat();

    @JsonProperty("value_inc_vat")
    double valueIncVat();

    @JsonProperty("valid_from")
    Instant validFrom();

    /// @return [Optional#empty()] for the current (open-ended) charge; populated for historical charges
    @JsonProperty("valid_to")
    Optional<Instant> validTo();
}
