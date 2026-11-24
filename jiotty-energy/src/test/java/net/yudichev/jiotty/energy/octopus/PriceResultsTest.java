package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.energy.EnergyPriceService.Failure;
import net.yudichev.jiotty.energy.PriceProfile;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.Prices;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PriceResultsTest {
    private static final Prices PRICES = new Prices(Instant.EPOCH, new PriceProfile(OctopusPriceSlots.SLOT_LENGTH_SEC, 1, List.of(1.0)));

    static Stream<Arguments> isUnchanged() {
        return Stream.of(
                // equal prices
                arguments(PRICES, new Prices(Instant.EPOCH, new PriceProfile(OctopusPriceSlots.SLOT_LENGTH_SEC, 1, List.of(1.0))), true),
                // retrieval errors whose causes have the same message
                arguments(retrievalError("down"), retrievalError("down"), true),
                // retrieval errors whose causes have different messages
                arguments(retrievalError("down"), retrievalError("refused"), false),
                // nothing published yet
                arguments(retrievalError("down"), null, false),
                // a retrieval error after prices
                arguments(retrievalError("down"), PRICES, false),
                // prices after a retrieval error
                arguments(PRICES, retrievalError("down"), false),
                // an incompatible tariff after a retrieval error
                arguments(new Failure.IncompatibleTariff("T"), retrievalError("down"), false));
    }

    @ParameterizedTest
    @MethodSource
    void isUnchanged(PriceResult result, @Nullable PriceResult currentResult, boolean expected) {
        assertThat(PriceResults.isUnchanged(result, currentResult)).isEqualTo(expected);
    }

    private static Failure.PriceRetrievalError retrievalError(String message) {
        return new Failure.PriceRetrievalError(new RuntimeException(message));
    }
}
