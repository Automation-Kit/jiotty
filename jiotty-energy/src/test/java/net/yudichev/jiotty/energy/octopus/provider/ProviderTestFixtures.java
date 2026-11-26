package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.ForecastEnergyPriceService;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/// Fixtures shared by the tests of [OctopusEnergyProviderService] and its nodes.
final class ProviderTestFixtures {
    private ProviderTestFixtures() {
    }

    /// @param validTo `null` for an agreement with no end date
    static Tariff createAgreement(String tariffCode, Instant validFrom, @Nullable Instant validTo) {
        return Tariff.builder().setTariffCode(tariffCode).setValidFrom(validFrom).setValidTo(validTo).build();
    }

    /// Hand-rolled fake of [ForecastEnergyPriceService] that lets tests observe the subscriber list and publish forecasts synchronously.
    static final class FakeForecastPriceService implements ForecastEnergyPriceService {
        private final List<Consumer<Prices>> subscribers = new ArrayList<>();

        @Override
        public Closeable subscribeToPrices(Consumer<Prices> consumer) {
            subscribers.add(consumer);
            return () -> subscribers.remove(consumer);
        }

        List<Consumer<Prices>> subscribers() {
            return subscribers;
        }

        void publish(Prices prices) {
            // A copy, because a subscriber can close its subscription during the delivery.
            for (var subscriber : new ArrayList<>(subscribers)) {
                subscriber.accept(prices);
            }
        }
    }
}
