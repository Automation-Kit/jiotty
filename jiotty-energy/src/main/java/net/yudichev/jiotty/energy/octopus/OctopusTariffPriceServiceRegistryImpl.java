package net.yudichev.jiotty.energy.octopus;

import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.connector.octopusenergy.MpanRegionResolver;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.connector.octopusenergy.ProductDetails;
import net.yudichev.jiotty.energy.TariffPricing;
import net.yudichev.jiotty.energy.octopus.OctopusTariffKinds.TariffKind;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.energy.octopus.Bindings.ExecutorProvider;
import static net.yudichev.jiotty.energy.octopus.OctopusTariffCode.productCode;
import static net.yudichev.jiotty.energy.octopus.OctopusTariffCode.region;

/// A lazy [OctopusTariffPriceServiceRegistry]. When a tariff is first asked for, the registry classifies it (an Agile tariff by its code alone, any other by
/// the product details that Octopus publish), creates and starts its price service, and keeps the result. Later requests get the same result; a request
/// after a failed classification classifies the tariff again.
///
/// Two accounts on the same Octopus tariff therefore share one price service and its retrieval. Two accounts on different products in the same region get
/// separate services, so each account's tariff resolves to its own rates.
///
/// Stopping the registry stops every service it created and closes every [OctopusRegionService] it opened.
public final class OctopusTariffPriceServiceRegistryImpl extends BaseLifecycleComponent implements OctopusTariffPriceServiceRegistry {
    private static final Logger logger = LogManager.getLogger(OctopusTariffPriceServiceRegistryImpl.class);

    private final Provider<SchedulingExecutor> executorProvider;
    private final OctopusEnergy octopusEnergy;
    private final OctopusTariffPriceServiceFactory factory;

    /// The region services opened so far.
    private final Map<Character, OctopusRegionService> regionServicesByRegion = new HashMap<>();
    /// The source of each tariff requested so far.
    private final Map<String, CompletableFuture<TariffPriceSource>> sourceFuturesByTariffCode = new HashMap<>();

    private SchedulingExecutor executor;

    @Inject
    public OctopusTariffPriceServiceRegistryImpl(@ExecutorProvider Provider<SchedulingExecutor> executorProvider,
                                                 OctopusEnergy octopusEnergy,
                                                 OctopusTariffPriceServiceFactory factory) {
        this.executorProvider = checkNotNull(executorProvider);
        this.octopusEnergy = checkNotNull(octopusEnergy);
        this.factory = checkNotNull(factory);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
    }

    /// Stops each priced service, including one whose classification is still running: its future completes only after the service has started, and the
    /// stop is chained onto it.
    @Override
    protected void doStop() {
        for (CompletableFuture<TariffPriceSource> sourceFuture : sourceFuturesByTariffCode.values()) {
            sourceFuture.thenAccept(source -> {
                if (source instanceof TariffPriceSource.Priced(var service, _, _)) {
                    closeSafelyIfNotNull(logger, service::stop);
                }
            });
        }
        closeSafelyIfNotNull(logger, regionServicesByRegion.values());
    }

    @Override
    public CompletableFuture<TariffPriceSource> forTariff(String tariffCode) {
        return whenStartedAndNotLifecycling(() -> sourceFuturesByTariffCode.compute(
                tariffCode,
                (_, existingSourceFuture) -> existingSourceFuture == null || existingSourceFuture.isCompletedExceptionally()
                                             ? resolve(tariffCode)
                                             : existingSourceFuture));
    }

    private CompletableFuture<TariffPriceSource> resolve(String tariffCode) {
        if (tariffCode.isEmpty() || !MpanRegionResolver.isValidRegion(region(tariffCode))) {
            return failedFuture(new IllegalArgumentException("Octopus tariff '" + tariffCode + "' names no known region"));
        }
        String productCode = productCode(tariffCode);
        if (OctopusTariffKinds.isAgile(productCode)) {
            return completedFuture(createAndStart(TariffKind.AGILE, productCode, tariffCode, false));
        }
        return executor.handleWhile(() -> true,
                                    octopusEnergy.getProductDetails(productCode),
                                    "classifyOctopusTariff",
                                    (details, failure) -> whenNotLifecycling(() -> {
                                        if (!isStartedPlain()) {
                                            return new CompletableFuture<>();
                                        }
                                        return failure == null ? completedFuture(classifyAndStart(details, productCode, tariffCode)) : failedFuture(failure);
                                    }));
    }

    private TariffPriceSource classifyAndStart(ProductDetails details, String productCode, String tariffCode) {
        return createAndStart(OctopusTariffKinds.classify(details, tariffCode), productCode, tariffCode, OctopusTariffKinds.supplierManagedCharging(details));
    }

    private TariffPriceSource createAndStart(TariffKind kind, String productCode, String tariffCode, boolean supplierManagedCharging) {
        logger.info("Octopus tariff {} is {}", tariffCode, kind);
        return switch (kind) {
            case AGILE -> new TariffPriceSource.Priced(start(factory.createAgile(regionService(tariffCode), productCode, tariffCode)),
                                                       TariffPricing.DYNAMIC,
                                                       false);
            case STANDARD_UNIT_RATES -> createAndStartPublished(RateSchedule.STANDARD_UNIT_RATES, productCode, tariffCode, supplierManagedCharging);
            case DAY_AND_NIGHT_RATES -> createAndStartPublished(RateSchedule.DAY_AND_NIGHT_RATES, productCode, tariffCode, supplierManagedCharging);
            case UNSUPPORTED -> TariffPriceSource.Unsupported.INSTANCE;
        };
    }

    private TariffPriceSource createAndStartPublished(RateSchedule rateSchedule, String productCode, String tariffCode, boolean supplierManagedCharging) {
        return new TariffPriceSource.Priced(start(factory.createPublished(regionService(tariffCode), rateSchedule, productCode, tariffCode)),
                                            TariffPricing.PUBLISHED,
                                            supplierManagedCharging);
    }

    private OctopusRegionService regionService(String tariffCode) {
        return regionServicesByRegion.computeIfAbsent(region(tariffCode), octopusEnergy::region);
    }

    private static OctopusTariffPriceService start(OctopusTariffPriceService service) {
        service.start();
        return service;
    }
}
