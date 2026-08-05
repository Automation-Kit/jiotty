package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

/// The api.x2r.uk response envelope.
@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseX2rResponse {

    X2rPrices prices();
}
