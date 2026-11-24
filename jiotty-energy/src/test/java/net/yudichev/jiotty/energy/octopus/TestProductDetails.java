package net.yudichev.jiotty.energy.octopus;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.connector.octopusenergy.OctopusEnergy;
import net.yudichev.jiotty.connector.octopusenergy.ProductDetails;
import net.yudichev.jiotty.connector.octopusenergy.TariffVariant;

import java.time.Instant;

/// A utility for building the [ProductDetails] that Octopus publish for a product.
final class TestProductDetails {
    private TestProductDetails() {
    }

    /// @return the variants of a tariff matrix in region `A`, with one direct-debit variant of `tariffCode`
    static ImmutableMap<String, ImmutableMap<String, TariffVariant>> createVariants(String tariffCode) {
        return ImmutableMap.of("_A",
                               ImmutableMap.of(OctopusEnergy.DIRECT_DEBIT_MONTHLY_PAYMENT_METHOD,
                                               TariffVariant.builder().setCode(tariffCode).setStandingChargeExcVat(50).setStandingChargeIncVat(50).build()));
    }

    static ProductDetails createProductDetails(String code,
                                               String displayName,
                                               boolean tracker,
                                               ImmutableMap<String, ImmutableMap<String, TariffVariant>> singleRegisterVariantsByPaymentMethodByRegion,
                                               ImmutableMap<String, ImmutableMap<String, TariffVariant>> fourRateVariantsByPaymentMethodByRegion) {
        return ProductDetails.builder()
                             .setCode(code)
                             .setDisplayName(displayName)
                             .setBrand("OCTOPUS_ENERGY")
                             .setTracker(tracker)
                             .setAvailableFrom(Instant.parse("2022-11-01T00:00:00Z"))
                             .setTariffsActiveAt(Instant.parse("2026-11-21T12:00:00Z"))
                             .putAllSingleRegisterElectricityTariffs(singleRegisterVariantsByPaymentMethodByRegion)
                             .putAllFourRateEvElectricityTariffs(fourRateVariantsByPaymentMethodByRegion)
                             .build();
    }
}
