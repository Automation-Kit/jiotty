package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.Prices;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

/// A utility for the prices that the Octopus price services under test publish.
public final class TestPrices {
    /// How far ahead of now the price services under test fetch prices and join agreements.
    public static final Duration FORWARD_WINDOW = Duration.ofDays(15);
    /// The number of half-hour slots in [#FORWARD_WINDOW].
    public static final int FORWARD_WINDOW_SLOTS = (int) (FORWARD_WINDOW.toSeconds() / OctopusPriceSlots.SLOT_LENGTH_SEC);

    private TestPrices() {
    }

    /// @return `slotCount` actual half-hour slots of `price` from `start`
    public static Prices createFlatPrices(Instant start, int slotCount, double price) {
        return new Prices(start, new PriceProfile(OctopusPriceSlots.SLOT_LENGTH_SEC, slotCount, Collections.nCopies(slotCount, price)));
    }
}
