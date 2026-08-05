package net.yudichev.jiotty.energy.octopus;

import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.ForecastPrice;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;

import java.time.Instant;
import java.util.List;

import static java.lang.Math.toIntExact;
import static net.yudichev.jiotty.energy.octopus.ForecastPriceProfiles.MIN_HORIZON;
import static net.yudichev.jiotty.energy.octopus.ForecastPriceProfiles.PRICE_PERIOD_LENGTH_SEC;

/// Builders of the forecast payloads and price profiles the price-forecast tests assert on. Sizes derive from the validation rules so that retuning a rule
/// moves the fixtures with it rather than turning a passing test vacuous.
final class ForecastFixtures {
    static final int PERIOD_SEC = PRICE_PERIOD_LENGTH_SEC;
    /// The fewest slots whose coverage satisfies [ForecastPriceProfiles#MIN_HORIZON].
    static final int MIN_HORIZON_SLOT_COUNT = toIntExact(MIN_HORIZON.getSeconds() / PERIOD_SEC);
    /// A comfortably valid payload size: past the minimum horizon, so a test that is not about the horizon never trips it.
    static final int TYPICAL_SLOT_COUNT = MIN_HORIZON_SLOT_COUNT + 8;

    private ForecastFixtures() {
    }

    /// Slots starting at `start`, each slot's price equal to its index.
    static List<ForecastPrice> slots(Instant start, int count) {
        var prices = ImmutableList.<ForecastPrice>builderWithExpectedSize(count);
        for (int i = 0; i < count; i++) {
            prices.add(ForecastPrice.builder()
                                    .setDateTime(start.plusSeconds((long) PERIOD_SEC * i))
                                    .setPredictedPrice(i)
                                    .build());
        }
        return prices.build();
    }

    /// The [Prices] profile [#slots] converts to.
    static Prices prices(Instant start, int count) {
        var values = ImmutableList.<Double>builderWithExpectedSize(count);
        for (int i = 0; i < count; i++) {
            values.add((double) i);
        }
        return new Prices(start, new PriceProfile(PERIOD_SEC, 0, values.build()));
    }
}
