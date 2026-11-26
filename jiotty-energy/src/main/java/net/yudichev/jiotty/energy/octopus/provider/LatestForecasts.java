package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.energy.Prices;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/// The latest price forecast of each supply region for which a forecast has arrived.
final class LatestForecasts {
    private final Map<Character, Prices> forecastsByRegion = new HashMap<>();

    public void record(char region, Prices forecast) {
        forecastsByRegion.put(region, forecast);
    }

    public Optional<Prices> forRegion(char region) {
        return Optional.ofNullable(forecastsByRegion.get(region));
    }
}
