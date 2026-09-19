package net.yudichev.jiotty.energy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PricesTest {
    @Test
    void limitTo() {
        var profile = new PriceProfile(60, 0, List.of(0.1, 0.2, 0.3)); // 3 min
        var prices = new Prices(Instant.EPOCH, profile);

        assertThat(prices.limitTo(Duration.ofMinutes(3).plusSeconds(1))).isSameAs(prices);

        var limitedPrices = prices.limitTo(Duration.ofMinutes(3).minusSeconds(1));
        assertThat(limitedPrices.profileStart()).isEqualTo(Instant.EPOCH);
        assertThat(limitedPrices.profile().intervalLengthSec()).isEqualTo(60);
        assertThat(limitedPrices.profile().pricePerInterval()).containsExactly(0.1, 0.2);

        limitedPrices = prices.limitTo(Duration.ofMinutes(1));
        assertThat(limitedPrices.profileStart()).isEqualTo(Instant.EPOCH);
        assertThat(limitedPrices.profile().intervalLengthSec()).isEqualTo(60);
        assertThat(limitedPrices.profile().pricePerInterval()).containsExactly(0.1);

        limitedPrices = prices.limitTo(Duration.ofSeconds(1));
        assertThat(limitedPrices.profileStart()).isEqualTo(Instant.EPOCH);
        assertThat(limitedPrices.profile().intervalLengthSec()).isEqualTo(60);
        assertThat(limitedPrices.profile().pricePerInterval()).isEmpty();
    }

    /// The index one past the last slot answers the profile's end, and does so on a freshly built profile — the end is memoised, so a caller that asks for it
    /// before anything else has must still get it.
    @Test
    void startOfProfileIndexAcceptsTheIndexPastTheLastSlotAndReturnsTheEnd() {
        var prices = new Prices(Instant.EPOCH, new PriceProfile(60, 0, List.of(0.1, 0.2, 0.3)));

        assertThat(prices.startOfProfileIndex(3)).isEqualTo(Instant.EPOCH.plus(Duration.ofMinutes(3)));
        assertThat(prices.startOfProfileIndex(0)).isEqualTo(Instant.EPOCH);
        assertThat(prices.startOfProfileIndex(2)).isEqualTo(Instant.EPOCH.plus(Duration.ofMinutes(2)));
    }

    /// Both ends are the point of it: a curve yet to open has none of its slots behind it, and one wholly behind reports all of them, where
    /// [Prices#profileIndexOf(Instant)] answers `-1` to both.
    @ParameterizedTest
    @CsvSource({"-1, 0", "0, 0", "59, 0", "60, 1", "61, 1", "119, 1", "179, 2", "180, 3", "6000, 3"})
    void firstUnendedProfileIndexCountsTheSlotsAlreadyBehindTheInstant(long offsetSec, int expectedIndex) {
        var prices = new Prices(Instant.EPOCH, new PriceProfile(60, 0, List.of(0.1, 0.2, 0.3)));

        assertThat(prices.firstUnendedProfileIndex(Instant.EPOCH.plusSeconds(offsetSec))).isEqualTo(expectedIndex);
    }

    /// The index one past the last slot is a legal argument to [Prices#startOfProfileIndex(int)], which is what lets a caller ask when an exhausted curve
    /// ended without a special case of its own.
    @Test
    void firstUnendedProfileIndexOfAnExhaustedCurveAddressesItsEnd() {
        var prices = new Prices(Instant.EPOCH, new PriceProfile(60, 0, List.of(0.1, 0.2, 0.3)));

        assertThat(prices.startOfProfileIndex(prices.firstUnendedProfileIndex(Instant.EPOCH.plusSeconds(6000)))).isEqualTo(prices.profileEnd());
    }

    /// An empty curve has no slot to be in and none still to run, so the two answers differ by shape rather than by value.
    @Test
    void anEmptyCurveHasNoSlotInProgressAndNoneLeft() {
        var prices = new Prices(Instant.EPOCH, new PriceProfile(60, 0, List.of()));

        assertThat(prices.profileIndexOf(Instant.EPOCH)).isEqualTo(-1);
        assertThat(prices.firstUnendedProfileIndex(Instant.EPOCH)).isZero();
    }
}