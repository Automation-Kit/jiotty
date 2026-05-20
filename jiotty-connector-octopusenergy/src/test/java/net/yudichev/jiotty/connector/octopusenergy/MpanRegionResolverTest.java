package net.yudichev.jiotty.connector.octopusenergy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MpanRegionResolverTest {

    @ParameterizedTest
    @CsvSource({
            "10, A",
            "11, B",
            "12, C",
            "13, D",
            "14, E",
            "15, F",
            "16, G",
            "17, H",
            "18, K",
            "19, J",
            "20, P",
            "21, L",
            "22, M",
            "23, N",
    })
    void resolveRegion_knownDistributorPrefix_returnsCorrespondingRegionLetter(String distributorId, char expectedRegion) {
        assertThat(MpanRegionResolver.resolveRegion(distributorId + "12345678901")).hasValue(expectedRegion);
    }

    @ParameterizedTest
    @CsvSource({
            "00, unknown leading prefix",
            "24, one past the last assigned",
            "99, far outside any assigned range",
    })
    void resolveRegion_unknownDistributorPrefix_returnsEmpty(String distributorId, String description) {
        assertThat(MpanRegionResolver.resolveRegion(distributorId + "12345678901"))
                .describedAs(description)
                .isEmpty();
    }

    @Test
    void resolveRegion_nullMpan_returnsEmpty() {
        assertThat(MpanRegionResolver.resolveRegion(null)).isEmpty();
    }

    @Test
    void resolveRegion_mpanShorterThanTwoCharacters_returnsEmpty() {
        assertThat(MpanRegionResolver.resolveRegion("")).isEmpty();
        assertThat(MpanRegionResolver.resolveRegion("1")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(chars = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N', 'P'})
    void isValidRegion_knownRegionLetter_returnsTrue(char regionLetter) {
        assertThat(MpanRegionResolver.isValidRegion(regionLetter)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(chars = {'I', 'O', 'Q', 'a', '0', ' '})
    void isValidRegion_unknownOrSkippedLetter_returnsFalse(char regionLetter) {
        // I and O are the deliberate gaps in the GB-DNO region set; lowercase, digits, whitespace are obviously invalid.
        assertThat(MpanRegionResolver.isValidRegion(regionLetter)).isFalse();
    }
}
