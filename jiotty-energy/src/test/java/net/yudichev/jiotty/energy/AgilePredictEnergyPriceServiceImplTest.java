package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

class AgilePredictEnergyPriceServiceImplTest {

    private ProgrammableClock clock;
    private AgilePredictEnergyPriceServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(i("06:25")); // time of the scheduled job run
        service = new AgilePredictEnergyPriceServiceImpl(
                () -> clock.createSingleThreadedSchedulingExecutor("executor"),
                (_, _) -> completedFuture(List.of(apPrice("07:00", 5),
                                                  apPrice("07:30", 6),
                                                  apPrice("09:00", 9), // AP bug - gap with 2 elements missing
                                                  apPrice("09:30", 10),
                                                  apPrice("10:00", 11))));
    }

    @Test
    void scenario() {
        service.start();
        clock.tick();

        var expected = new Prices(i("07:00"), new PriceProfile(30 * 60, 0, List.of(5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)));
        assertThat(service.getResult()).hasValue(Either.left(expected));
    }

    private static AgilePredictPrice apPrice(String time, double predictedPrice) {
        return AgilePredictPrice.builder()
                                .setDateTime(i(time))
                                .setPredictedPrice(predictedPrice)
                                .build();
    }

    private static Instant i(String str) {
        return Instant.parse("2024-01-01T" + str + ":00Z");
    }
}
