package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.connector.octopusenergy.priceforecast.ForecastPrice;
import net.yudichev.jiotty.energy.Prices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static java.lang.Math.toIntExact;
import static net.yudichev.jiotty.energy.octopus.ForecastFixtures.MIN_HORIZON_SLOT_COUNT;
import static net.yudichev.jiotty.energy.octopus.ForecastFixtures.PERIOD_SEC;
import static net.yudichev.jiotty.energy.octopus.ForecastFixtures.TYPICAL_SLOT_COUNT;
import static net.yudichev.jiotty.energy.octopus.ForecastFixtures.prices;
import static net.yudichev.jiotty.energy.octopus.ForecastFixtures.slots;
import static net.yudichev.jiotty.energy.octopus.ForecastPriceProfiles.INTERPOLATED_GAP;
import static net.yudichev.jiotty.energy.octopus.ForecastPriceProfiles.MAX_PLAUSIBLE_PRICE;
import static net.yudichev.jiotty.energy.octopus.ForecastPriceProfiles.MAX_SLOT_COUNT;
import static net.yudichev.jiotty.energy.octopus.ForecastPriceProfiles.MAX_START_DELAY;
import static net.yudichev.jiotty.energy.octopus.ForecastPriceProfiles.MIN_PLAUSIBLE_MEDIAN_ABS_PRICE;
import static net.yudichev.jiotty.energy.octopus.ForecastPriceProfiles.MIN_PLAUSIBLE_PRICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ForecastPriceProfilesTest {

    private static final Instant NOW = Instant.parse("2024-01-01T06:25:00Z");
    /// How many slots [ForecastPriceProfiles#INTERPOLATED_GAP] spans, and so how many must be dropped to produce a gap of exactly that width.
    private static final int INTERPOLATED_GAP_SLOT_COUNT = toIntExact(INTERPOLATED_GAP.getSeconds() / PERIOD_SEC);

    @Test
    void acceptsContiguousForecast() {
        assertThat(ForecastPriceProfiles.toPrices(slots(NOW, TYPICAL_SLOT_COUNT), NOW, "test"))
                .isEqualTo(prices(NOW, TYPICAL_SLOT_COUNT));
    }

    @Test
    void acceptsForecastEndingExactlyAtMinimumHorizon() {
        assertThat(ForecastPriceProfiles.toPrices(slots(NOW, MIN_HORIZON_SLOT_COUNT), NOW, "test"))
                .isEqualTo(prices(NOW, MIN_HORIZON_SLOT_COUNT));
    }

    @Test
    void sortsUnsortedInput() {
        assertThat(ForecastPriceProfiles.toPrices(slots(NOW, TYPICAL_SLOT_COUNT).reversed(), NOW, "test"))
                .isEqualTo(prices(NOW, TYPICAL_SLOT_COUNT));
    }

    @Test
    void interpolatesOverSingleOneHourGap() {
        // slot values grow linearly with the index, so the linearly interpolated values equal the removed originals and the resulting profile is the
        // gap-free one
        var slots = new ArrayList<>(slots(NOW, TYPICAL_SLOT_COUNT));
        slots.subList(10, 10 + INTERPOLATED_GAP_SLOT_COUNT).clear();

        assertThat(ForecastPriceProfiles.toPrices(slots, NOW, "test")).isEqualTo(prices(NOW, TYPICAL_SLOT_COUNT));
    }

    @ParameterizedTest
    @MethodSource
    void rejectsMalformedPayload(String expectedMessagePart, List<ForecastPrice> prices) {
        assertThatThrownBy(() -> ForecastPriceProfiles.toPrices(prices, NOW, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedMessagePart);
    }

    static List<Arguments> rejectsMalformedPayload() {
        var missingOneSlot = new ArrayList<>(slots(NOW, TYPICAL_SLOT_COUNT));
        missingOneSlot.remove(10);
        var missingThreeSlots = new ArrayList<>(slots(NOW, TYPICAL_SLOT_COUNT));
        missingThreeSlots.subList(10, 13).clear();
        var duplicateSlot = new ArrayList<>(slots(NOW, TYPICAL_SLOT_COUNT));
        duplicateSlot.add(duplicateSlot.get(10));
        return List.of(arguments("empty list of prices", List.of()),
                       arguments("exceed the maximum", slots(NOW, MAX_SLOT_COUNT + 1)),
                       arguments("must have start time", missingOneSlot),
                       arguments("must have start time", missingThreeSlots),
                       arguments("must have start time", duplicateSlot),
                       arguments("earlier than", slots(NOW, MIN_HORIZON_SLOT_COUNT - 1)),
                       arguments("later than", slots(NOW.plus(MAX_START_DELAY).plusSeconds(PERIOD_SEC), TYPICAL_SLOT_COUNT)));
    }

    @ParameterizedTest
    @ValueSource(doubles = {MAX_PLAUSIBLE_PRICE + 1.0, MIN_PLAUSIBLE_PRICE - 1.0})
    void rejectsPriceOutsidePlausibleRange(double implausiblePrice) {
        assertThatThrownBy(() -> ForecastPriceProfiles.toPrices(slotsWithPriceAt(10, implausiblePrice), NOW, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the plausible range");
    }

    @ParameterizedTest
    @ValueSource(doubles = {MAX_PLAUSIBLE_PRICE, MIN_PLAUSIBLE_PRICE})
    void acceptsPriceAtEitherEdgeOfThePlausibleRange(double edgePrice) {
        List<ForecastPrice> slots = slotsWithPriceAt(10, edgePrice);

        Prices prices = ForecastPriceProfiles.toPrices(slots, NOW, "test");

        assertThat(prices.profile().pricePerInterval().get(10)).isEqualTo(edgePrice);
    }

    @Test
    void rejectsImplausiblyLowMedianIndicatingScaleError() {
        // a payload in pounds rather than pence passes the per-price range check; the median rule is what catches it
        double poundsRatherThanPence = MIN_PLAUSIBLE_MEDIAN_ABS_PRICE / 4.0;
        var slots = new ArrayList<ForecastPrice>(TYPICAL_SLOT_COUNT);
        for (int i = 0; i < TYPICAL_SLOT_COUNT; i++) {
            slots.add(ForecastPrice.builder()
                                   .setDateTime(NOW.plusSeconds((long) PERIOD_SEC * i))
                                   .setPredictedPrice(poundsRatherThanPence)
                                   .build());
        }

        assertThatThrownBy(() -> ForecastPriceProfiles.toPrices(slots, NOW, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unit or scale error");
    }

    /// The typical forecast with the slot at `index` replaced by one priced `price`.
    private static List<ForecastPrice> slotsWithPriceAt(int index, double price) {
        var slots = new ArrayList<>(slots(NOW, TYPICAL_SLOT_COUNT));
        slots.set(index, ForecastPrice.builder()
                                      .setDateTime(slots.get(index).dateTime())
                                      .setPredictedPrice(price)
                                      .build());
        return slots;
    }

}
