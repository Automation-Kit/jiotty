package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.util.List;

/// One forecast from the response envelope shared by agilepredict.com and agileforecast.co.uk: both serve a JSON array of these, with matching slot fields,
/// so both parse into the same [ForecastPrice]s.
@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseAgilePredictPricesResponse {

    List<ForecastPrice> prices();
}
