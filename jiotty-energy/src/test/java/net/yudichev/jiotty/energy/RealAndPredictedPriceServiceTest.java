package net.yudichev.jiotty.energy;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.security.AuthState;
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
    private Optional<Prices> realPrices;
    private Optional<Prices> predictedPrices;
    private Consumer<Prices> realPriceConsumer;
    private Consumer<Prices> predictedPriceConsumer;
    @Mock
    private Closeable realPriceSubscription;
    @Mock
    private Closeable predictedPriceSubscription;
    private RealAndPredictedPriceService service;

    @BeforeEach
    void setUp() {
        service = new RealAndPredictedPriceService(new EnergyPriceService() {
            @Override
            public Optional<Prices> getPrices() {
                return realPrices;
            }

            @Override
            public Closeable subscribeToPrices(Consumer<Prices> consumer) {
                assertThat(realPriceConsumer).isNull();
                realPriceConsumer = consumer;
                return realPriceSubscription;
            }

            @Override
            public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
                consumer.accept(new AuthState.Success("SUCCESS"));
                return () -> {};
            }
        }, new EnergyPriceService() {
            @Override
            public Optional<Prices> getPrices() {
                return predictedPrices;
            }

            @Override
            public Closeable subscribeToPrices(Consumer<Prices> consumer) {
                assertThat(predictedPriceConsumer).isNull();
                predictedPriceConsumer = consumer;
                return predictedPriceSubscription;
            }

            @Override
            public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
                throw new UnsupportedOperationException();
            }
        });
    }

    @ParameterizedTest
    @MethodSource
    void combinesGetPrices(Optional<Prices> realPrices, Optional<Prices> predictedPrices, Optional<Prices> expectedResult) {
        this.realPrices = realPrices;
        this.predictedPrices = predictedPrices;
        assertThat(service.getPrices()).isEqualTo(expectedResult);
    }

    static Stream<Arguments> combinesGetPrices() {
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
    void combinesSubscriptions() {
        List<Prices> receivedPrices = new ArrayList<>();
        Closeable subscription = service.subscribeToPrices(receivedPrices::add);
        assertThat(receivedPrices).isEmpty();

        Prices realPrices = p("00:00", 1, 0.0);
        Prices predictedPrices = p("00:30", 0, 1.0);

        predictedPriceConsumer.accept(predictedPrices);
        assertThat(receivedPrices).isEmpty();

        realPriceConsumer.accept(realPrices);
        assertThat(receivedPrices).containsExactly(p("00:00", 1, 0.0, 1.0));

        verifyNoMoreInteractions(realPriceSubscription, predictedPriceSubscription);
        subscription.close();
        verify(realPriceSubscription).close();
        verify(predictedPriceSubscription).close();
    }

    @Test
    void realPricesUsedWithoutPredictedOnSubscription() {
        List<Prices> receivedPrices = new ArrayList<>();
        service.subscribeToPrices(receivedPrices::add);
        assertThat(receivedPrices).isEmpty();

        Prices prices = p("00:00", 0, 0.0);

        realPriceConsumer.accept(prices);
        assertThat(receivedPrices).containsExactly(prices);
    }

    static Prices p(String start, int idxOfPredictedPriceStart, Double... elements) {
        return new Prices(i(start), new PriceProfile(Math.toIntExact(MINUTES.toSeconds(30)), idxOfPredictedPriceStart, List.of(elements)));
    }

    private static Instant i(String str) {
        return str.length() == 5 ? Instant.parse("2024-01-01T" + str + ":00Z")
                                 : Instant.parse("2024-01-01T" + str + "Z");
    }

}