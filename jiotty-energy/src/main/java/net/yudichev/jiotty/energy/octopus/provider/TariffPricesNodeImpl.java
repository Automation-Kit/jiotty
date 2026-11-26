package net.yudichev.jiotty.energy.octopus.provider;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.graph.server.BaseServerNode;
import net.yudichev.jiotty.common.graph.server.GraphRunner;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.energy.PriceResult;
import net.yudichev.jiotty.energy.octopus.OctopusTariffPriceService;
import net.yudichev.jiotty.energy.octopus.TariffPriceSource;
import net.yudichev.jiotty.energy.octopus.provider.AgreementPrices.TariffPriceState;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Failed;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolved;
import net.yudichev.jiotty.energy.octopus.provider.TariffResolution.Resolving;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

/// A [TariffPricesNode] that subscribes to the prices and the next refresh time of each priced tariff while that tariff is in the forward window. Each
/// result and next refresh time that a tariff's price service delivers is stored until the next wave.
final class TariffPricesNodeImpl extends BaseServerNode implements TariffPricesNode {
    private final TariffSourcesNode tariffSourcesNode;
    /// The subscription to the price service of each priced tariff in the forward window.
    private final Map<String, PriceSubscription> priceSubscriptionsByTariffCode = new HashMap<>();

    private @Nullable ImmutableMap<String, TariffPriceState> priceStatesByTariffCode;

    public TariffPricesNodeImpl(GraphRunner runner, TariffSourcesNode tariffSourcesNode) {
        super(runner);
        this.tariffSourcesNode = subscribeTo(checkNotNull(tariffSourcesNode));
    }

    /// Closes the subscriptions, so that a delivery after the close is dropped.
    @Override
    public void close() {
        closeSafelyIfNotNull(logger, priceSubscriptionsByTariffCode.values());
        priceSubscriptionsByTariffCode.clear();
        super.close();
    }

    @Override
    public @Nullable Map<String, TariffPriceState> priceStatesByTariffCode() {
        return priceStatesByTariffCode;
    }

    @Override
    public void logState(String when) {
        logger.debug("{}: priceSubscriptionsByTariffCode={}, priceStatesByTariffCode={}", when, priceSubscriptionsByTariffCode, priceStatesByTariffCode);
    }

    @Override
    protected boolean doWave() {
        Map<String, TariffResolution> resolutionsByTariffCode = tariffSourcesNode.resolutionsByTariffCode();
        if (resolutionsByTariffCode == null) {
            return false;
        }
        priceSubscriptionsByTariffCode.entrySet().removeIf(entry -> {
            if (resolutionsByTariffCode.get(entry.getKey()) instanceof Resolved(TariffPriceSource.Priced priced)
                && priced.service() == entry.getValue().service) {
                return false;
            }
            closeIfNotNull(entry.getValue());
            return true;
        });
        ImmutableMap.Builder<String, TariffPriceState> newPriceStates = ImmutableMap.builderWithExpectedSize(resolutionsByTariffCode.size());
        resolutionsByTariffCode.forEach((tariffCode, resolution) -> newPriceStates.put(tariffCode, switch (resolution) {
            case Resolving _ -> TariffPriceState.Resolving.INSTANCE;
            case Failed(Throwable cause) -> new TariffPriceState.ResolutionFailed(cause);
            case Resolved(TariffPriceSource.Unsupported _) -> TariffPriceState.Unsupported.INSTANCE;
            case Resolved(TariffPriceSource.Priced(var service, var pricing, _)) ->
                    priceSubscriptionsByTariffCode.computeIfAbsent(tariffCode, _ -> new PriceSubscription(service)).applyDeliveries(pricing.isDynamic());
        }));
        ImmutableMap<String, TariffPriceState> newPriceStatesByTariffCode = newPriceStates.build();
        boolean changed = !newPriceStatesByTariffCode.equals(priceStatesByTariffCode);
        priceStatesByTariffCode = newPriceStatesByTariffCode;
        return changed;
    }

    /// The subscriptions to the prices and the next refresh time of one priced tariff's service.
    private final class PriceSubscription extends BaseIdempotentCloseable implements StringFormattable {
        private final OctopusTariffPriceService service;
        private final Closeable pricesSubscription;
        private final Closeable nextRefreshTimeSubscription;
        /// The latest result that the service has delivered since the last wave; `null` when it has delivered none.
        private @Nullable PriceResult pendingResult;
        /// The latest next refresh time that the service has delivered since the last wave; `null` when it has delivered none.
        private @Nullable Instant pendingNextRefreshTime;
        /// `null` until the service has delivered a result.
        private @Nullable PriceResult result;
        /// `null` until the service has delivered a next refresh time.
        private @Nullable Instant nextRefreshTime;

        PriceSubscription(OctopusTariffPriceService service) {
            this.service = checkNotNull(service);
            pricesSubscription = service.subscribeToPrices(value -> runner.executor().tryExecute("onTariffPrices", () -> {
                if (!isClosedPlain()) {
                    pendingResult = value;
                    triggerInNewWave("tariff prices delivered");
                }
            }));
            nextRefreshTimeSubscription = service.subscribeToNextRefreshTime(value -> runner.executor().tryExecute("onTariffRefreshTime", () -> {
                if (!isClosedPlain()) {
                    pendingNextRefreshTime = value;
                    triggerInNewWave("tariff refresh time delivered");
                }
            }));
        }

        @Override
        protected void doClose() {
            closeIfNotNull(pricesSubscription, nextRefreshTimeSubscription);
        }

        /// Applies the result and the next refresh time that the service has delivered since the last wave.
        ///
        /// @param dynamic whether the prices of the tariff are dynamic
        /// @return the price state of the tariff with every delivery applied
        public TariffPriceState applyDeliveries(boolean dynamic) {
            if (pendingResult != null) {
                result = pendingResult;
                pendingResult = null;
            }
            if (pendingNextRefreshTime != null) {
                nextRefreshTime = pendingNextRefreshTime;
                pendingNextRefreshTime = null;
            }
            return new TariffPriceState.Priced(Optional.ofNullable(result), Optional.ofNullable(nextRefreshTime), dynamic);
        }

        @Override
        public void formatTo(Appendable appendable) {
            Append.to(appendable, "{pendingResultDelivered=");
            Append.to(appendable, pendingResult != null);
            Append.to(appendable, ", pendingNextRefreshTime=");
            Append.to(appendable, pendingNextRefreshTime);
            Append.to(appendable, '}');
        }

        @Override
        public String toString() {
            return toString(64);
        }
    }
}
