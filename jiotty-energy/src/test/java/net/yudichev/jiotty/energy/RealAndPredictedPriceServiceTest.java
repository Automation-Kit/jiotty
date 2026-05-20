package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.security.AuthState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RealAndPredictedPriceServiceTest {
    private Optional<Either<Prices, EnergyPriceService.Failure>> realResult;
    private Optional<Either<Prices, EnergyPriceService.Failure>> predictedResult;
    private Consumer<Either<Prices, EnergyPriceService.Failure>> realConsumer;
    private Consumer<Either<Prices, EnergyPriceService.Failure>> predictedConsumer;
    @Mock
    private Closeable realSubscription;
    @Mock
    private Closeable predictedSubscription;
    private RealAndPredictedPriceService service;

    @BeforeEach
    void setUp() {
        realResult = empty();
        predictedResult = empty();
        service = new RealAndPredictedPriceService(new EnergyPriceService() {
            @Override
            public Optional<Either<Prices, Failure>> getResult() {
                return realResult;
            }

            @Override
            public Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer) {
                assertThat(realConsumer).isNull();
                realConsumer = consumer;
                return realSubscription;
            }

            @Override
            public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
                consumer.accept(new AuthState.Success("SUCCESS"));
                return () -> {};
            }
        }, new EnergyPriceService() {
            @Override
            public Optional<Either<Prices, Failure>> getResult() {
                return predictedResult;
            }

            @Override
            public Closeable subscribeToPrices(Consumer<Either<Prices, Failure>> consumer) {
                assertThat(predictedConsumer).isNull();
                predictedConsumer = consumer;
                return predictedSubscription;
            }

            @Override
            public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
                throw new UnsupportedOperationException();
            }
        });
    }

    @ParameterizedTest
    @MethodSource
    void combinesGetResult(Optional<Prices> realPrices, Optional<Prices> predictedPrices, Optional<Prices> expected) {
        realResult = realPrices.map(Either::left);
        predictedResult = predictedPrices.map(Either::left);

        Optional<Prices> actual = service.getResult().flatMap(Either::getLeft);
        assertThat(actual).isEqualTo(expected);
    }

    static Stream<Arguments> combinesGetResult() {
        return Stream.of(
                Arguments.of(empty(), empty(), empty()),
                Arguments.of(empty(), of(p("00:00", 0, 5.0)), empty()),
                Arguments.of(of(p("00:00", 1, 5.0)), empty(), of(p("00:00", 1, 5.0))),
                Arguments.of(of(p("00:00", 4, 0.0, 1.0, 2.0, 3.0)), of(p("00:30", 0, 1.1, 2.1, 3.1, 4.1, 5.1)),
                             of(p("00:00", 4, 0.0, 1.0, 2.0, 3.0, 4.1, 5.1))),
                Arguments.of(of(p("00:30", 4, 0.0, 1.0, 2.0, 3.0)), of(p("00:00", 0, -0.1, 0.0, 1.1, 2.1, 3.1, 4.1, 5.1)),
                             of(p("00:30", 4, 0.0, 1.0, 2.0, 3.0, 4.1, 5.1))),
                Arguments.of(of(p("00:00", 4, 0.0, 1.0, 2.0, 3.0)), of(p("02:00", 0, 4.1, 5.1)),
                             of(p("00:00", 4, 0.0, 1.0, 2.0, 3.0, 4.1, 5.1))),
                Arguments.of(of(p("03:00", 4, 0.0, 1.0, 2.0, 3.0)), of(p("00:00", 0, 4.1, 5.1)),
                             of(p("03:00", 4, 0.0, 1.0, 2.0, 3.0)))
        );
    }

    @Test
    void realFailureSurfacesUnchanged() {
        var failure = new EnergyPriceService.Failure.PriceRetrievalError(new RuntimeException("boom"));
        realResult = of(Either.right(failure));
        predictedResult = of(Either.left(p("00:00", 0, 1.0)));

        var combined = service.getResult().orElseThrow();
        assertThat(combined.getRight()).contains(failure);
    }

    @Test
    void combinesSubscriptions() {
        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        Closeable subscription = service.subscribeToPrices(received::add);
        assertThat(received).isEmpty();

        Prices realPrices = p("00:00", 1, 0.0);
        Prices predictedPrices = p("00:30", 0, 1.0);

        predictedConsumer.accept(Either.left(predictedPrices));
        assertThat(received).isEmpty();

        realConsumer.accept(Either.left(realPrices));
        assertThat(received).containsExactly(Either.left(p("00:00", 1, 0.0, 1.0)));

        verifyNoMoreInteractions(realSubscription, predictedSubscription);
        subscription.close();
        verify(realSubscription).close();
        verify(predictedSubscription).close();
    }

    @Test
    void realFailureForwardedFromSubscription() {
        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.subscribeToPrices(received::add);

        var failure = new EnergyPriceService.Failure.IncompatibleTariff("E-1R-GO-VAR-22-10-14-A");
        realConsumer.accept(Either.right(failure));

        assertThat(received).containsExactly(Either.right(failure));
    }

    @Test
    void realPricesUsedWithoutPredictedOnSubscription() {
        List<Either<Prices, EnergyPriceService.Failure>> received = new ArrayList<>();
        service.subscribeToPrices(received::add);
        assertThat(received).isEmpty();

        Prices prices = p("00:00", 0, 0.0);

        realConsumer.accept(Either.left(prices));
        assertThat(received).containsExactly(Either.left(prices));
    }

    static Prices p(String start, int idxOfPredictedPriceStart, Double... elements) {
        return new Prices(i(start), new PriceProfile(Math.toIntExact(MINUTES.toSeconds(30)), idxOfPredictedPriceStart, List.of(elements)));
    }

    private static Instant i(String str) {
        return str.length() == 5 ? Instant.parse("2024-01-01T" + str + ":00Z")
                                 : Instant.parse("2024-01-01T" + str + "Z");
    }
}
