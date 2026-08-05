package net.yudichev.jiotty.energy.octopus;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.PriceForecastSource;
import org.immutables.value.Value;

import java.time.Instant;
import java.util.List;

/// The last successfully fetched forecast of one region, persisted so a freshly started service can serve it until the first fetch succeeds.
@Value.Immutable
@PublicImmutablesStyle
@JsonSerialize
@JsonDeserialize
interface BaseStoredForecast {
    /// When the forecast was fetched; bounds how long the stored copy is trusted after a restart.
    Instant savedAt();

    /// [PriceForecastSource#name()] of the source that served the forecast.
    String source();

    /// Start of the first price period.
    Instant profileStart();

    int intervalLengthSec();

    /// Predicted price per interval in p/kWh including VAT.
    List<Double> prices();
}
