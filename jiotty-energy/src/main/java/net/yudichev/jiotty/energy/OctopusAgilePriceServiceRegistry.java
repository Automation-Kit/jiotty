package net.yudichev.jiotty.energy;

import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

/// App-scope lazy registry of [OctopusAgilePriceServiceImpl] instances keyed by `tariffCode`. The first [#forTariff] call for a given tariff opens an
/// [OctopusRegionService] for the tariff's region (or reuses an already-opened one), constructs the per-tariff impl via the injected
/// [OctopusAgilePriceService.Factory], starts it, and caches the result. Repeat calls return the same impl instance.
///
/// Two users on the same Octopus tariff therefore share the same daily 16:05 fetch and the same `octopus.rates:<productCode>:<tariffCode>` rows. Two users on
/// different AGILE products in the same region get separate impls so each user's tariff resolves to its own rate stream.
///
/// Closing the registry closes every created impl and every opened region service.
public final class OctopusAgilePriceServiceRegistry extends BaseLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(OctopusAgilePriceServiceRegistry.class);
    private static final String AGILE_PRODUCT_CODE_PREFIX = "AGILE-";

    private final OctopusEnergy octopusEnergy;
    private final OctopusAgilePriceService.Factory factory;

    private final ConcurrentMap<Character, OctopusRegionService> regionServicesByRegion = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OctopusAgilePriceService> servicesByTariffCode = new ConcurrentHashMap<>();

    @Inject
    public OctopusAgilePriceServiceRegistry(OctopusEnergy octopusEnergy,
                                            OctopusAgilePriceService.Factory factory) {
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.factory = checkNotNull(factory);
    }

    /// Returns the cached Agile-prices service for `(productCode, tariffCode)`, creating and starting a new one on first call. By construction this registry
    /// only serves AGILE tariffs; non-Agile inputs throw [IllegalArgumentException].
    public EnergyPriceService forTariff(String productCode, String tariffCode) {
        checkNotNull(productCode, "productCode");
        checkNotNull(tariffCode, "tariffCode");
        checkArgument(productCode.startsWith(AGILE_PRODUCT_CODE_PREFIX), "expected Agile product code, got %s", productCode);
        return whenStartedAndNotLifecycling(() -> servicesByTariffCode.computeIfAbsent(tariffCode,
                                                                                       _ -> createAndStart(productCode, tariffCode)));
    }

    private OctopusAgilePriceService createAndStart(String productCode, String tariffCode) {
        char regionLetter = tariffCode.charAt(tariffCode.length() - 1);
        OctopusRegionService regionService = regionServicesByRegion.computeIfAbsent(regionLetter, octopusEnergy::region);
        var impl = factory.create(regionService, productCode, tariffCode);
        impl.start();
        logger.info("Created and started Octopus Agile price service for tariff {}", tariffCode);
        return impl;
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, servicesByTariffCode.values().stream().<Closeable>map(s -> s::stop).toList());
        servicesByTariffCode.clear();
        closeSafelyIfNotNull(logger, regionServicesByRegion.values());
        regionServicesByRegion.clear();
    }
}
