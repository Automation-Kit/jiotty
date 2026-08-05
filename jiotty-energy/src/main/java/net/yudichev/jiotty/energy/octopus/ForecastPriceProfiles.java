package net.yudichev.jiotty.energy.octopus;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.ForecastPrice;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.StrictMath.toIntExact;
import static java.util.Comparator.comparing;

/// Validates a fetched forecast and converts it to [Prices]. Forecast payloads come from third-party services, so beyond requiring a contiguous half-hourly
/// grid this rejects payloads whose size, price scale or time coverage indicate the source is broken — served prices feed charge planning, so a plausible but
/// wrong payload is worse than none.
final class ForecastPriceProfiles {
    /// A 13-day forecast is 624 slots; anything past this bounds a runaway payload.
    @VisibleForTesting
    static final int MAX_SLOT_COUNT = 2000;
    /// Sanity range in p/kWh, inclusive at both ends: the Agile cap is 100 p inc VAT and negative prices bottom out well above -100.
    @VisibleForTesting
    static final double MIN_PLAUSIBLE_PRICE = -100.0;
    @VisibleForTesting
    static final double MAX_PLAUSIBLE_PRICE = 300.0;
    /// A payload in £/kWh passes the range check; typical Agile medians are 10-30 p/kWh, so a median absolute price below this means a unit or scale error.
    @VisibleForTesting
    static final double MIN_PLAUSIBLE_MEDIAN_ABS_PRICE = 1.0;
    /// Octopus actual prices already cover up to ~31h ahead, so a forecast ending before this adds nothing and indicates a stale or truncated payload.
    @VisibleForTesting
    static final Duration MIN_HORIZON = Duration.ofHours(36);
    /// Forecasts start at the current half-hour; a first slot this far ahead means a clock or payload error.
    @VisibleForTesting
    static final Duration MAX_START_DELAY = Duration.ofHours(24);
    @VisibleForTesting
    static final int PRICE_PERIOD_LENGTH_SEC = toIntExact(TimeUnit.MINUTES.toSeconds(30));
    /// The one gap width the DST bug below produces, and so the only one interpolated over rather than rejected.
    @VisibleForTesting
    static final Duration INTERPOLATED_GAP = Duration.ofHours(1);
    private static final Logger logger = LogManager.getLogger(ForecastPriceProfiles.class);

    private ForecastPriceProfiles() {
    }

    /// Validates `prices` against the rules above and converts them to a half-hourly all-predicted [Prices] profile, interpolating over a single 1-hour gap.
    ///
    /// @param logContext prefix identifying the region and source in log lines
    /// @throws IllegalArgumentException if the payload is empty, oversized, non-contiguous, implausibly priced or covers the wrong time window
    static Prices toPrices(List<ForecastPrice> prices, Instant now, String logContext) {
        checkArgument(!prices.isEmpty(), "empty list of prices");
        checkArgument(prices.size() <= MAX_SLOT_COUNT, "%s slots exceed the maximum of %s", prices.size(), MAX_SLOT_COUNT);

        // observed returned prices not sorted by date, so sort it first
        prices = new ArrayList<>(prices);
        prices.sort(comparing(ForecastPrice::dateTime));

        Instant startOfOldestPricePeriod = prices.getFirst().dateTime();
        Instant endOfPrices = prices.getLast().dateTime().plusSeconds(PRICE_PERIOD_LENGTH_SEC);
        checkArgument(!startOfOldestPricePeriod.isAfter(now.plus(MAX_START_DELAY)),
                      "prices start at %s, later than %s after the current time %s", startOfOldestPricePeriod, MAX_START_DELAY, now);
        checkArgument(!endOfPrices.isBefore(now.plus(MIN_HORIZON)),
                      "prices end at %s, earlier than %s after the current time %s", endOfPrices, MIN_HORIZON, now);

        logger.debug("[{}] Prices received: {}", logContext, prices);
        var newPricesPerPeriodBuilder = ImmutableList.<Double>builderWithExpectedSize(prices.size() + 2);
        Instant expectedStartTime = startOfOldestPricePeriod;
        double minPrice = Double.POSITIVE_INFINITY;
        double maxPrice = Double.NEGATIVE_INFINITY;
        var absPrices = new double[prices.size()];
        for (int i = 0; i < prices.size(); i++) {
            ForecastPrice price = prices.get(i);
            var diffFromExpectedToActualTime = Duration.between(expectedStartTime, price.dateTime());
            if (diffFromExpectedToActualTime.equals(INTERPOLATED_GAP)) {
                // Handle DST bug https://github.com/fboundy/agile_predict/issues/11 TODO remove when fixed
                // add two missing prices with linearly interpolated values
                assert i > 0; // the expected time of element 0 is its own time, so a gap can only appear from element 1 on
                ForecastPrice previousPrice = prices.get(i - 1);
                double oneThirdOfTheDiff = (price.predictedPrice() - previousPrice.predictedPrice()) / 3.0;
                newPricesPerPeriodBuilder.add(previousPrice.predictedPrice() + oneThirdOfTheDiff);
                newPricesPerPeriodBuilder.add(previousPrice.predictedPrice() + oneThirdOfTheDiff * 2.0);
                expectedStartTime = expectedStartTime.plusSeconds(PRICE_PERIOD_LENGTH_SEC * 2L);
            } else {
                checkArgument(diffFromExpectedToActualTime.isZero(),
                              "Element %s in received prices must have start time %s but was %s: %s",
                              i, expectedStartTime, price.dateTime(), prices);
            }
            double priceValue = price.predictedPrice();
            checkArgument(priceValue >= MIN_PLAUSIBLE_PRICE && priceValue <= MAX_PLAUSIBLE_PRICE,
                          "price %s at %s is outside the plausible range [%s, %s]", priceValue, price.dateTime(), MIN_PLAUSIBLE_PRICE, MAX_PLAUSIBLE_PRICE);
            minPrice = Math.min(minPrice, priceValue);
            maxPrice = Math.max(maxPrice, priceValue);
            absPrices[i] = Math.abs(priceValue);
            newPricesPerPeriodBuilder.add(priceValue);
            expectedStartTime = expectedStartTime.plusSeconds(PRICE_PERIOD_LENGTH_SEC);
        }
        Arrays.sort(absPrices);
        double medianAbsPrice = absPrices[absPrices.length / 2];
        checkArgument(medianAbsPrice >= MIN_PLAUSIBLE_MEDIAN_ABS_PRICE,
                      "median absolute price %s is below %s, indicating a unit or scale error", medianAbsPrice, MIN_PLAUSIBLE_MEDIAN_ABS_PRICE);

        logger.info("[{}] Accepted forecast: {} slots from {} till {}, p/kWh min {} median {} max {}",
                    logContext, prices.size(), startOfOldestPricePeriod, endOfPrices, minPrice, medianAbsPrice, maxPrice);
        return new Prices(startOfOldestPricePeriod, new PriceProfile(PRICE_PERIOD_LENGTH_SEC, 0, newPricesPerPeriodBuilder.build()));
    }
}
