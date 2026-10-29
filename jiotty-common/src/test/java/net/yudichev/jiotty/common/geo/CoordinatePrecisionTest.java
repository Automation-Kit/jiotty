package net.yudichev.jiotty.common.geo;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CoordinatePrecisionTest {
    @ParameterizedTest
    @CsvSource({
            "ONE_DECIMAL_PLACE, 51.50735, 51.5",
            "ONE_DECIMAL_PLACE, -0.12776, -0.1",
            "TWO_DECIMAL_PLACES, 51.50735, 51.51",
            "TWO_DECIMAL_PLACES, -0.12776, -0.13",
            // carries into the whole degree
            "TWO_DECIMAL_PLACES, 52.996, 53.0",
            // a tie goes towards positive infinity
            "TWO_DECIMAL_PLACES, -0.125, -0.12",
            // a value that rounds to zero from below gives positive zero
            "TWO_DECIMAL_PLACES, -0.004, 0.0",
            // NaN and infinity are returned unchanged
            "ONE_DECIMAL_PLACE, NaN, NaN",
            "TWO_DECIMAL_PLACES, -Infinity, -Infinity",
    })
    void roundsToItsPrecision(CoordinatePrecision precision, double coordinate, double expected) {
        // Boxed, so the comparison tells positive zero from negative zero.
        assertThat(Double.valueOf(precision.round(coordinate))).isEqualTo(Double.valueOf(expected));
    }
}
