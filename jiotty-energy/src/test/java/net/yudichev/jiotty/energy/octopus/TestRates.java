package net.yudichev.jiotty.energy.octopus;

import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

import static net.yudichev.jiotty.common.lang.EvenMoreObjects.mapIfNotNull;

/// A utility for building Octopus standard unit rate rows in tests. Each row has the same price with and without VAT.
public final class TestRates {
    private TestRates() {
    }

    /// @return a row with no end date that applies to every payment method
    public static StandardUnitRate createRate(String validFrom, double price) {
        return buildRate(validFrom, null, price, null);
    }

    /// @param validTo the row's exclusive end
    /// @return a row that applies to every payment method
    public static StandardUnitRate createRate(String validFrom, String validTo, double price) {
        return buildRate(validFrom, validTo, price, null);
    }

    /// @param paymentMethod the payment method to which the row applies
    /// @return a row with no end date
    public static StandardUnitRate createRate(String validFrom, double price, String paymentMethod) {
        return buildRate(validFrom, null, price, paymentMethod);
    }

    /// @param validTo       the row's exclusive end, `null` for a row with no end date
    /// @param paymentMethod the payment method to which the row applies, `null` for a row that applies to every payment method
    private static StandardUnitRate buildRate(String validFrom, @Nullable String validTo, double price, @Nullable String paymentMethod) {
        return StandardUnitRate.builder()
                               .setValidFrom(Instant.parse(validFrom))
                               .setValidTo(mapIfNotNull(validTo, Instant::parse))
                               .setValueExcVat(price)
                               .setValueIncVat(price)
                               .setPaymentMethod(paymentMethod)
                               .build();
    }
}
