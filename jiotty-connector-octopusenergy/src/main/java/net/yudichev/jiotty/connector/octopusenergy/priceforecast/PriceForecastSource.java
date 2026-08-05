package net.yudichev.jiotty.connector.octopusenergy.priceforecast;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/// One provider of Octopus Agile price forecasts. Sources are interchangeable: every source returns equivalent half-hourly [ForecastPrice]s for a GB region.
public interface PriceForecastSource {
    /// Stable identifier of this source: a short lowercase word that never changes for a given source.
    String name();

    /// Fetches the latest forecast: half-hourly prices in p/kWh including VAT, starting at or before the current half-hour.
    ///
    /// @param region   single-letter GB region code
    /// @param dayCount number of days requested, 1 to 365; a source with a shorter fixed horizon returns what it has
    CompletableFuture<List<ForecastPrice>> getPrices(String region, int dayCount);
}
