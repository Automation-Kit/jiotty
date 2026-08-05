package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.List;

/// The price arrays of an api.x2r.uk response. The response also carries `day_ahead` and `actual` arrays whose slots duplicate the instants in [#forecast()],
/// so [#forecast()] is the one array consumed.
@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseX2rPrices {

    List<X2rForecastPrice> forecast();
}
