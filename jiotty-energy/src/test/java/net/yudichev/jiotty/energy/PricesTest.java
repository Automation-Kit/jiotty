package net.yudichev.jiotty.energy;

import org.junit.jupiter.api.Test;

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
}