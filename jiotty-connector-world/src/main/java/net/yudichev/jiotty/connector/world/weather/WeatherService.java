package net.yudichev.jiotty.connector.world.weather;

import net.yudichev.jiotty.common.geo.LatLon;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface WeatherService {
    int MAX_FORECAST_DAYS = 13;

    CompletableFuture<Weather> getCurrentWeather(LatLon worldCoordinates);

    /// @return forecasted weather for the specified world coordinates at the specified instant.
    /// @throws IllegalArgumentException if `until` is in the past or too far in the future (more than [#MAX_FORECAST_DAYS] days).
    CompletableFuture<List<ForecastHour>> getForecastWeather(LatLon worldCoordinates, Instant until);
}
