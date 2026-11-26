package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.energy.Prices;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static net.yudichev.jiotty.energy.octopus.TestPrices.createFlatPrices;
import static org.assertj.core.api.Assertions.assertThat;

class LatestForecastsTest {
    private static final Instant START = Instant.parse("2024-01-01T10:00:00Z");

    private final LatestForecasts latestForecasts = new LatestForecasts();

    @Test
    void eachRegion_holdsItsLatestForecast() {
        Prices latestForecast = createFlatPrices(START, 48, 21.0);
        latestForecasts.record('A', createFlatPrices(START, 48, 20.0));
        latestForecasts.record('A', latestForecast);

        assertThat(latestForecasts.forRegion('A')).hasValue(latestForecast);
        assertThat(latestForecasts.forRegion('B')).isEmpty();
    }
}
