package net.yudichev.jiotty.energy.octopus;

import com.google.inject.assistedinject.Assisted;
import net.yudichev.jiotty.connector.octopusenergy.OctopusRegionService;
import net.yudichev.jiotty.energy.octopus.tariff.OctopusAgilePriceServiceImpl;
import net.yudichev.jiotty.energy.octopus.tariff.OctopusPublishedRatePriceServiceImpl;

/// A factory of the [OctopusTariffPriceService] of one Octopus tariff: [OctopusAgilePriceServiceImpl] for an Agile tariff and
/// [OctopusPublishedRatePriceServiceImpl] for a tariff with published rates.
public interface OctopusTariffPriceServiceFactory {
    OctopusAgilePriceServiceImpl createAgile(OctopusRegionService regionService,
                                             @Assisted("productCode") String productCode,
                                             @Assisted("tariffCode") String tariffCode);

    OctopusPublishedRatePriceServiceImpl createPublished(OctopusRegionService regionService,
                                                         RateSchedule rateSchedule,
                                                         @Assisted("productCode") String productCode,
                                                         @Assisted("tariffCode") String tariffCode);
}
