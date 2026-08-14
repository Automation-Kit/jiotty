package net.yudichev.jiotty.common.geo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatLonTest {
    @Test
    void toStringCoarsensSoAnEmbeddingTypeCannotLeakAPreciseCoordinate() {
        assertThat(new LatLon(51.501234, -0.142567))
                .asString()
                .isEqualTo("~{51.5,-0.1}")
                .doesNotContain("501234")
                .doesNotContain("142567");
    }

    @Test
    void formatToWritesTheSameCoarseFormIntoACallerSuppliedBuffer() {
        var buffer = new StringBuilder("at ");
        new LatLon(51.501234, -0.142567).formatTo(buffer);
        assertThat(buffer.toString()).isEqualTo("at ~{51.5,-0.1}");
    }

    @Test
    void accessorsKeepFullPrecisionSoSerialisationAndArithmeticAreUnaffected() {
        var latLon = new LatLon(51.501234, -0.142567);
        assertThat(latLon.lat()).isEqualTo(51.501234);
        assertThat(latLon.lon()).isEqualTo(-0.142567);
    }
}
