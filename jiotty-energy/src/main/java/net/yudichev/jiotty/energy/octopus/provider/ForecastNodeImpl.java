package net.yudichev.jiotty.energy.octopus.provider;

import com.google.inject.assistedinject.Assisted;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.Tariff;
import net.yudichev.jiotty.energy.Prices;
import net.yudichev.jiotty.energy.octopus.ForecastEnergyPriceService;
import net.yudichev.jiotty.energy.octopus.OctopusTariffCode;
import net.yudichev.jiotty.energy.octopus.PriceForecastServiceRegistry;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolved;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;

/// A [ForecastNode] that subscribes to the forecast for the region of the first agreement in the forward window whose tariff has dynamic prices. When that
/// region changes, the node closes the subscription and subscribes to the forecast for the new region. Each forecast that the [ForecastEnergyPriceService]
/// of the region delivers is stored until the next wave, which records it in the [LatestForecasts]. The node publishes the forecast recorded there for its
/// region, so the node of a graph rebuilt after a panic starts from the forecast that the node of the previous graph received.
final class ForecastNodeImpl extends BaseServerNode implements ForecastNode {
    private final PriceForecastServiceRegistry forecastRegistry;
    private final AgreementWindowNode agreementWindowNode;
    private final TariffSourcesNode tariffSourcesNode;
    private final LatestForecasts latestForecasts;

    /// The latest forecast that the subscription has delivered since the last wave; `null` when it has delivered none.
    private @Nullable Prices pendingForecast;
    /// The region whose forecast this node subscribes to; `null` while no agreement in the forward window has a tariff with dynamic prices.
    private @Nullable Character region;
    /// `null` while no agreement in the forward window has a tariff with dynamic prices.
    private @Nullable ForecastSubscription subscription;
    private @Nullable Forecast forecast;

    @Inject
    public ForecastNodeImpl(PriceForecastServiceRegistry forecastRegistry,
                            @Assisted GraphRunner runner,
                            @Assisted AgreementWindowNode agreementWindowNode,
                            @Assisted TariffSourcesNode tariffSourcesNode,
                            @Assisted LatestForecasts latestForecasts) {
        super(runner);
        this.forecastRegistry = checkNotNull(forecastRegistry);
        this.agreementWindowNode = subscribeTo(checkNotNull(agreementWindowNode));
        this.tariffSourcesNode = subscribeTo(checkNotNull(tariffSourcesNode));
        this.latestForecasts = checkNotNull(latestForecasts);
    }

    /// Closes the subscription, so that a forecast delivered after the close is dropped.
    @Override
    public void close() {
        closeIfNotNull(subscription);
        super.close();
    }

    @Override
    public @Nullable Forecast forecast() {
        return forecast;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: pendingForecastDelivered={}, region={}, subscribed={}, forecast={}",
                     when, pendingForecast != null, region, subscription != null, forecast);
    }

    @Override
    protected boolean doWave() {
        List<Tariff> agreements = agreementWindowNode.agreements();
        Map<String, TariffResolution> resolutionsByTariffCode = tariffSourcesNode.resolutionsByTariffCode();
        if (agreements == null || resolutionsByTariffCode == null) {
            return false;
        }
        Forecast previousForecast = forecast;
        updateRegion(dynamicRegion(agreements, resolutionsByTariffCode));
        Prices pendingForecast = this.pendingForecast;
        this.pendingForecast = null;
        if (region == null) {
            forecast = Forecast.None.INSTANCE;
        } else {
            if (pendingForecast != null) {
                latestForecasts.record(region, pendingForecast);
            }
            forecast = latestForecasts.forRegion(region).<Forecast>map(Forecast.Available::new).orElse(Forecast.None.INSTANCE);
        }
        return !forecast.equals(previousForecast);
    }

    /// @return the region of the first agreement whose tariff is resolved to dynamic prices, `null` when there is none
    private static @Nullable Character dynamicRegion(List<Tariff> agreements, Map<String, TariffResolution> resolutionsByTariffCode) {
        for (Tariff agreement : agreements) {
            if (resolutionsByTariffCode.get(agreement.tariffCode()) instanceof Resolved(TariffPriceSource.Priced(_, var pricing, _)) && pricing.isDynamic()) {
                return OctopusTariffCode.region(agreement.tariffCode());
            }
        }
        return null;
    }

    /// Subscribes to the forecast of `newRegion` in place of the current subscription, unless `newRegion` is the current region.
    ///
    /// @param newRegion the region to subscribe to; `null` to subscribe to none
    private void updateRegion(@Nullable Character newRegion) {
        if (Objects.equals(newRegion, region)) {
            return;
        }
        logger.info("Forecast region changed: {} → {}", region, newRegion);
        closeIfNotNull(subscription);
        subscription = null;
        pendingForecast = null;
        region = newRegion;
        if (newRegion != null) {
            subscription = new ForecastSubscription(newRegion);
        }
    }

    private final class ForecastSubscription extends BaseIdempotentCloseable {
        private final Closeable delegate;

        /// Subscribes to the forecast of `region`.
        ForecastSubscription(char region) {
            delegate = forecastRegistry.forRegion(region).subscribeToPrices(prices -> runner.executor().tryExecute("onForecastPrices", () -> {
                if (!isClosedPlain()) {
                    pendingForecast = prices;
                    triggerInNewWave("price forecast delivered");
                }
            }));
        }

        @Override
        protected void doClose() {
            closeIfNotNull(delegate);
        }
    }

    interface Factory {
        /// @param latestForecasts where this node records each forecast delivered, and from which it reads the forecast of its region
        ForecastNodeImpl create(GraphRunner runner,
                                AgreementWindowNode agreementWindowNode,
                                TariffSourcesNode tariffSourcesNode,
                                LatestForecasts latestForecasts);
    }
}
