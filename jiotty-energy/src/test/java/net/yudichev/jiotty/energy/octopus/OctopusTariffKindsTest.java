package net.yudichev.jiotty.energy.octopus;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.connector.octopusenergy.ProductDetails;
import net.yudichev.jiotty.energy.octopus.OctopusTariffKinds.TariffKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static net.yudichev.jiotty.energy.octopus.TestProductDetails.createProductDetails;
import static net.yudichev.jiotty.energy.octopus.TestProductDetails.createVariants;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class OctopusTariffKindsTest {
    @Test
    void agileProduct_isAgile() {
        ProductDetails details = createProductDetails("AGILE-24-10-01", "Agile Octopus", false, createVariants("E-1R-AGILE-24-10-01-A"), ImmutableMap.of());

        assertThat(OctopusTariffKinds.isAgile("AGILE-24-10-01")).isTrue();
        assertThat(OctopusTariffKinds.isAgile("VAR-22-11-01")).isFalse();
        assertThat(OctopusTariffKinds.classify(details, "E-1R-AGILE-24-10-01-A")).isEqualTo(TariffKind.AGILE);
    }

    static Stream<Arguments> classify() {
        return Stream.of(
                // a single-register tariff is priced from its standard unit rates
                arguments(createProductDetails("VAR-22-11-01", "Flexible Octopus", false, createVariants("E-1R-VAR-22-11-01-A"), ImmutableMap.of()),
                          "E-1R-VAR-22-11-01-A",
                          TariffKind.STANDARD_UNIT_RATES),
                // a four-rate tariff is priced from its day and night rates
                arguments(createProductDetails("IOG-SMB-VAR-26-11-04",
                                               "Intelligent Octopus Go",
                                               false,
                                               ImmutableMap.of(),
                                               createVariants("E-1R-IOG-SMB-VAR-26-11-04-A")),
                          "E-1R-IOG-SMB-VAR-26-11-04-A",
                          TariffKind.DAY_AND_NIGHT_RATES),
                // a Tracker is unsupported
                arguments(createProductDetails("SILVER-24-12-31", "Octopus Tracker", true, createVariants("E-1R-SILVER-24-12-31-A"), ImmutableMap.of()),
                          "E-1R-SILVER-24-12-31-A",
                          TariffKind.UNSUPPORTED),
                // a dual-register tariff is unsupported
                arguments(createProductDetails("VAR-22-11-01", "Flexible Octopus", false, createVariants("E-1R-VAR-22-11-01-A"), ImmutableMap.of()),
                          "E-2R-VAR-22-11-01-A",
                          TariffKind.UNSUPPORTED));
    }

    @ParameterizedTest
    @MethodSource
    void classify(ProductDetails details, String tariffCode, TariffKind expectedKind) {
        assertThat(OctopusTariffKinds.classify(details, tariffCode)).isEqualTo(expectedKind);
    }

    @ParameterizedTest
    @CsvSource({"IOG-SMB-VAR-26-11-04, Intelligent Octopus Go, true",
            "INTELLI-VAR-24-10-29, Intelligent Octopus Go, true",
            "INTELLI-FIX-12M-25-07-01, Intelligent Octopus Go 12M Fixed, true",
            "INTELLI-VAR-OEV-24-10-29, Intelligent Octopus Go – EV Saver, true",
            "INTELLI-FLUX-IMPORT-23-07-14, Intelligent Octopus Flux, false",
            "GO-VAR-22-10-14, Octopus Go, false",
            "VAR-22-11-01, Flexible Octopus, false",
            "AGILE-24-10-01, Agile Octopus, false"})
    void supplierManagedCharging_isIntelligentOctopusGo(String productCode, String displayName, boolean expected) {
        ProductDetails details = createProductDetails(productCode, displayName, false, ImmutableMap.of(), ImmutableMap.of());

        assertThat(OctopusTariffKinds.supplierManagedCharging(details)).isEqualTo(expected);
    }
}
