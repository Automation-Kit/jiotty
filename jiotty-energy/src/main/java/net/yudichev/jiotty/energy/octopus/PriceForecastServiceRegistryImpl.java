package net.yudichev.jiotty.energy.octopus;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.priceforecast.PriceForecastSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

/// Constructs each per-region service via the injected [ForecastEnergyPriceServiceImpl.Factory], starts it, and caches the result. The forecast
/// surface is region-only, so the key is a single char.
///
/// Closing the registry closes every created service. The underlying [PriceForecastSource]s' lifecycle is owned by their installing module.
public final class PriceForecastServiceRegistryImpl extends BaseLifecycleComponent implements PriceForecastServiceRegistry {
    private static final Logger logger = LogManager.getLogger(PriceForecastServiceRegistryImpl.class);

    private final ForecastEnergyPriceServiceImpl.Factory factory;

    /// Guarded by the lifecycle lock (all access flows through [#whenStartedAndNotLifecycling]).
    private final Map<Character, ForecastEnergyPriceServiceImpl> servicesByRegion = new HashMap<>();

    @Inject
    public PriceForecastServiceRegistryImpl(ForecastEnergyPriceServiceImpl.Factory factory) {
        this.factory = checkNotNull(factory);
    }

    @Override
    public ForecastEnergyPriceService forRegion(char regionLetter) {
        return whenStartedAndNotLifecycling(() -> servicesByRegion.computeIfAbsent(regionLetter, this::createAndStart));
    }

    private ForecastEnergyPriceServiceImpl createAndStart(char regionLetter) {
        ForecastEnergyPriceServiceImpl impl = factory.create(regionLetter);
        impl.start();
        logger.info("Created and started price forecast service for region {}", regionLetter);
        return impl;
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, servicesByRegion.values().stream().<Closeable>map(s -> s::stop).toList());
        servicesByRegion.clear();
    }
}
