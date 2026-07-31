package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.misc.UpstreamHealthHandler;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPrice;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgilePredictEnergyPriceServiceImplTest {

    @Mock
    private UpstreamHealthHandler statusHandler;

    private ProgrammableClock clock;

    @BeforeEach
    void setUp() {
        clock = new ProgrammableClock();
        clock.setTime(i("06:25")); // time of the scheduled job run
    }

    @Test
    void scenario() {
        AgilePredictEnergyPriceServiceImpl service = create((_, _) -> completedFuture(List.of(apPrice("07:00", 5),
                                                                                              apPrice("07:30", 6),
                                                                                              apPrice("09:00", 9), // AP bug - gap with 2 elements missing
                                                                                              apPrice("09:30", 10),
                                                                                              apPrice("10:00", 11))));
        service.start();
        clock.tick();

        var expected = new Prices(i("07:00"), new PriceProfile(30 * 60, 0, List.of(5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)));
        assertThat(service.getPrices()).hasValue(Either.left(expected));
        verify(statusHandler, atLeastOnce()).onSuccess();
        verify(statusHandler, never()).onFailure(any(), any());
    }

    @Test
    void notifiesFailureWhenRetriesOverrunRefreshPeriod() {
        AgilePredictEnergyPriceServiceImpl service = create((_, _) -> failedFuture(new RuntimeException("boom")));
        service.start();
        clock.tick(); // initial retrieval fails and schedules a retry, no failure reported yet

        // advance to the next refresh while retries are still pending -> the next refresh reports the overrun
        clock.advanceTimeAndTick(AgilePredictEnergyPriceServiceImpl.RETRIEVAL_PERIOD);

        verify(statusHandler, atLeastOnce()).onFailure(anyString(), any());
    }

    private AgilePredictEnergyPriceServiceImpl create(AgilePredictPriceService priceService) {
        return new AgilePredictEnergyPriceServiceImpl(
                () -> clock.createSingleThreadedSchedulingExecutor("executor"),
                priceService,
                statusHandler,
                'A');
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
