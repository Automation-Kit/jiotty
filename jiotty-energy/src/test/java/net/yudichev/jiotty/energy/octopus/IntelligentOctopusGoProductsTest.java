package net.yudichev.jiotty.energy.octopus;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class IntelligentOctopusGoProductsTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
                                            INTELLI-VAR-24-10-29         | Intelligent Octopus Go       | true
                                            intelli-var-24-10-29         | intelligent octopus go       | true
                                            IOG-SMB-VAR-26-11-04         | 12M Fixed EV Saver           | true
                                            SMART-EV-25-01-01            | Intelligent Octopus Go       | true
                                            INTELLI-FLUX-IMPORT-23-07-14 | Intelligent Octopus Flux     | false
                                            GO-VAR-22-10-14              | Octopus Go                   | false
                                            AGILE-24-10-01               | Agile Octopus                | false
                                            """)
    void isIntelligentOctopusGo_needsAnIntelligentAndAGoMarker(String productCode, String productName, boolean expected) {
        assertThat(IntelligentOctopusGoProducts.isIntelligentOctopusGo(productCode, productName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            // 23:30 GMT, the window's start
            "2026-11-21T23:30:00Z, true",
            // 05:29 GMT, inside the window after midnight
            "2026-11-22T05:29:00Z, true",
            // 05:30 GMT, the window's end
            "2026-11-22T05:30:00Z, false",
            // 23:29 GMT, before the window
            "2026-11-21T23:29:00Z, false",
            // 23:30 BST, the window's start in summer
            "2026-07-01T22:30:00Z, true",
            // 05:30 BST, the window's end in summer
            "2026-07-02T04:30:00Z, false"})
    void isOffPeak_followsTheUkClock(Instant instant, boolean expected) {
        assertThat(IntelligentOctopusGoProducts.isOffPeak(instant)).isEqualTo(expected);
    }
}
