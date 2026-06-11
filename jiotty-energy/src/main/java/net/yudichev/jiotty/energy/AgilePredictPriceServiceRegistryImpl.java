package net.yudichev.jiotty.energy;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

/// Constructs each per-region service via the injected [AgilePredictEnergyPriceServiceImpl.Factory], starts it, and caches the result. The AgilePredict
/// surface is region-only, so the key is a single char.
///
/// Closing the registry closes every created service. The underlying [AgilePredictPriceService] connector's lifecycle is owned by its installing module.
public final class AgilePredictPriceServiceRegistryImpl extends BaseLifecycleComponent implements AgilePredictPriceServiceRegistry {
    private static final Logger logger = LogManager.getLogger(AgilePredictPriceServiceRegistryImpl.class);

    private final AgilePredictEnergyPriceServiceImpl.Factory factory;

    /// Guarded by the lifecycle lock (all access flows through [#whenStartedAndNotLifecycling]).
    private final Map<Character, AgilePredictEnergyPriceService> servicesByRegion = new HashMap<>();

    @Inject
    public AgilePredictPriceServiceRegistryImpl(AgilePredictEnergyPriceServiceImpl.Factory factory) {
        this.factory = checkNotNull(factory);
    }

    @Override
    public EnergyPriceService forRegion(char regionLetter) {
        return whenStartedAndNotLifecycling(() -> servicesByRegion.computeIfAbsent(regionLetter, this::createAndStart));
    }

    private AgilePredictEnergyPriceService createAndStart(char regionLetter) {
        AgilePredictEnergyPriceService impl = factory.create(regionLetter);
        impl.start();
        logger.info("Created and started AgilePredict price service for region {}", regionLetter);
        return impl;
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, servicesByRegion.values().stream().<Closeable>map(s -> s::stop).toList());
        servicesByRegion.clear();
    }
}
