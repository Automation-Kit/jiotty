package net.yudichev.jiotty.energy;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.agilepredict.AgilePredictPriceService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

/// Lazy registry of [AgilePredictEnergyPriceServiceImpl] instances keyed by region letter. The first [#forRegion] call for a given region constructs the
/// per-region impl via the injected [AgilePredictEnergyPriceServiceImpl.Factory], starts it, and caches the result. Repeat calls return the same instance.
///
/// The AgilePredict surface is region-only, so the key is a single char.
///
/// Closing the registry closes every created impl. The underlying [AgilePredictPriceService] connector's lifecycle is owned by its installing module.
public final class AgilePredictPriceServiceRegistry extends BaseLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(AgilePredictPriceServiceRegistry.class);

    private final AgilePredictEnergyPriceServiceImpl.Factory factory;

    private final ConcurrentMap<Character, AgilePredictEnergyPriceService> servicesByRegion = new ConcurrentHashMap<>();

    @Inject
    public AgilePredictPriceServiceRegistry(AgilePredictEnergyPriceServiceImpl.Factory factory) {
        this.factory = checkNotNull(factory);
    }

    /// Returns the cached AgilePredict service for `regionLetter`, creating and starting a new one on first call.
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
