package net.yudichev.jiotty.energy.octopus;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

final class OctopusTariffCodeTest {
    @ParameterizedTest(name = "[{index}] {0} → product {1}")
    @CsvSource({
            "E-1R-AGILE-24-10-01-A, AGILE-24-10-01",               // single-rate
            "E-2R-INTELLI-VAR-22-10-14-A, INTELLI-VAR-22-10-14",   // two-rate
            "E-FLAT2R-SILVER-23-12-06-A, SILVER-23-12-06",         // wide (6-char) rate-type segment must not be mis-parsed as a fixed-width substring
            "NONSENSE, NONSENSE",                                  // unrecognised shape is returned unchanged (never throws)
    })
    void productCode(String tariffCode, String expectedProductCode) {
        assertThat(OctopusTariffCode.productCode(tariffCode)).isEqualTo(expectedProductCode);
    }
}
