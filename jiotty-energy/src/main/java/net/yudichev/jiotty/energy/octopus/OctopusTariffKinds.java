package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.connector.octopusenergy.ProductDetails;
import net.yudichev.jiotty.connector.octopusenergy.TariffVariant;

import java.util.Map;

import static net.yudichev.jiotty.energy.octopus.OctopusTariffCode.AGILE_PRODUCT_CODE_PREFIX;

/// A utility for classifying an Octopus electricity tariff from its product's details.
final class OctopusTariffKinds {
    private OctopusTariffKinds() {
    }

    /// How the prices of a tariff are retrieved.
    public enum TariffKind {
        /// Half-hourly Agile rates published a day ahead, with a forecast beyond.
        AGILE,
        /// Published standard unit rates: one flat price, or bands by time of day.
        STANDARD_UNIT_RATES,
        /// Published day and night unit rates, laid on the Intelligent Octopus Go off-peak window.
        DAY_AND_NIGHT_RATES,
        /// No prices can be retrieved: a Tracker, whose price changes daily and is not published ahead, or a tariff with separate day and night meter
        /// registers (Economy 7).
        UNSUPPORTED
    }

    /// @return whether `productCode` is the code of an Agile product, which the code alone identifies
    public static boolean isAgile(String productCode) {
        return productCode.startsWith(AGILE_PRODUCT_CODE_PREFIX);
    }

    /// @return how the prices of `tariffCode`, a tariff of the product described by `details`, are retrieved
    public static TariffKind classify(ProductDetails details, String tariffCode) {
        if (isAgile(details.code())) {
            return TariffKind.AGILE;
        }
        if (details.tracker()) {
            return TariffKind.UNSUPPORTED;
        }
        if (containsTariff(details.singleRegisterElectricityTariffs(), tariffCode)) {
            return TariffKind.STANDARD_UNIT_RATES;
        }
        if (containsTariff(details.fourRateEvElectricityTariffs(), tariffCode)) {
            return TariffKind.DAY_AND_NIGHT_RATES;
        }
        return TariffKind.UNSUPPORTED;
    }

    /// @return whether Octopus manages the charging of the customer's car on the product described by `details`
    public static boolean supplierManagedCharging(ProductDetails details) {
        return IntelligentOctopusGoProducts.isIntelligentOctopusGo(details.code(), details.displayName());
    }

    private static boolean containsTariff(Map<String, Map<String, TariffVariant>> variantsByPaymentMethodByRegion, String tariffCode) {
        for (Map<String, TariffVariant> variantsByPaymentMethod : variantsByPaymentMethodByRegion.values()) {
            for (TariffVariant variant : variantsByPaymentMethod.values()) {
                if (variant.code().equals(tariffCode)) {
                    return true;
                }
            }
        }
        return false;
    }
}
