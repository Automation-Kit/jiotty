package net.yudichev.jiotty.energy.octopus.provider;

import net.yudichev.jiotty.common.graph.server.BaseTestServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.TariffTimeline;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.TariffPriceState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/// Nodes of the [OctopusEnergyProviderService] graph whose values a test sets. Each `set` triggers the node in the next wave.
final class FakeProviderNodes {
    private FakeProviderNodes() {
    }

    static final class FakeAccountNode extends BaseTestServerNode implements AccountNode {
        private @Nullable AccountState state;

        FakeAccountNode(GraphRunner graphRunner) {
            super(graphRunner);
        }

        @Override
        public @Nullable AccountState state() {
            return state;
        }

        void set(AccountState value) {
            state = value;
            triggerInNextWave();
        }
    }

    static final class FakeAgreementWindowNode extends BaseTestServerNode implements AgreementWindowNode {
        private @Nullable List<Tariff> agreements;

        FakeAgreementWindowNode(GraphRunner graphRunner) {
            super(graphRunner);
        }

        @Override
        public @Nullable List<Tariff> agreements() {
            return agreements;
        }

        void set(List<Tariff> value) {
            agreements = value;
            triggerInNextWave();
        }
    }

    static final class FakeTariffSourcesNode extends BaseTestServerNode implements TariffSourcesNode {
        private @Nullable Map<String, TariffResolution> resolutionsByTariffCode;

        FakeTariffSourcesNode(GraphRunner graphRunner) {
            super(graphRunner);
        }

        @Override
        public @Nullable Map<String, TariffResolution> resolutionsByTariffCode() {
            return resolutionsByTariffCode;
        }

        void set(Map<String, TariffResolution> value) {
            resolutionsByTariffCode = value;
            triggerInNextWave();
        }
    }

    static final class FakeTariffPricesNode extends BaseTestServerNode implements TariffPricesNode {
        private @Nullable Map<String, TariffPriceState> priceStatesByTariffCode;

        FakeTariffPricesNode(GraphRunner graphRunner) {
            super(graphRunner);
        }

        @Override
        public @Nullable Map<String, TariffPriceState> priceStatesByTariffCode() {
            return priceStatesByTariffCode;
        }

        void set(Map<String, TariffPriceState> value) {
            priceStatesByTariffCode = value;
            triggerInNextWave();
        }
    }

    static final class FakeForecastNode extends BaseTestServerNode implements ForecastNode {
        private @Nullable Forecast forecast;

        FakeForecastNode(GraphRunner graphRunner) {
            super(graphRunner);
        }

        @Override
        public @Nullable Forecast forecast() {
            return forecast;
        }

        void set(Forecast value) {
            forecast = value;
            triggerInNextWave();
        }
    }

    static final class FakeTariffTimelineNode extends BaseTestServerNode implements TariffTimelineNode {
        private @Nullable TariffTimeline timeline;

        FakeTariffTimelineNode(GraphRunner graphRunner) {
            super(graphRunner);
        }

        @Override
        public @Nullable TariffTimeline timeline() {
            return timeline;
        }

        void set(TariffTimeline value) {
            timeline = value;
            triggerInNextWave();
        }
    }
}
