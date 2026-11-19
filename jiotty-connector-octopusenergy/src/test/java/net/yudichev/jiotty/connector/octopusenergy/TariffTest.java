package net.yudichev.jiotty.connector.octopusenergy;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static net.yudichev.jiotty.common.lang.EvenMoreObjects.mapIfNotNull;
import static org.assertj.core.api.Assertions.assertThat;

class TariffTest {
    @ParameterizedTest
    @CsvSource({
            "2024-02-01T00:00:00Z, 2024-01-01T00:00:00Z, true",
            "2024-02-01T00:00:00Z, 2023-12-31T23:59:59Z, false",
            "2024-02-01T00:00:00Z, 2024-02-01T00:00:00Z, false",
            "2024-02-01T00:00:00Z, 2024-01-31T23:59:59Z, true",
            ",                     2099-01-01T00:00:00Z, true",
            ",                     2023-12-31T23:59:59Z, false",
    })
    void covers_includesTheStartAndExcludesTheEnd(@Nullable String validTo, String instant, boolean expected) {
        assertThat(createTariff(validTo).covers(Instant.parse(instant))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "2024-02-01T00:00:00Z, 2023-12-01T00:00:00Z, 2024-01-01T00:00:00Z, false",
            "2024-02-01T00:00:00Z, 2023-12-01T00:00:00Z, 2024-01-01T00:00:01Z, true",
            "2024-02-01T00:00:00Z, 2024-02-01T00:00:00Z, 2024-03-01T00:00:00Z, false",
            "2024-02-01T00:00:00Z, 2024-01-31T23:59:59Z, 2024-03-01T00:00:00Z, true",
            ",                     2099-01-01T00:00:00Z, 2099-01-02T00:00:00Z, true",
            ",                     2023-12-01T00:00:00Z, 2024-01-01T00:00:00Z, false",
    })
    void overlaps_treatsBothWindowsAsHalfOpen(@Nullable String validTo, String from, String to, boolean expected) {
        assertThat(createTariff(validTo).overlaps(Instant.parse(from), Instant.parse(to))).isEqualTo(expected);
    }

    /// Builds an agreement starting at 2024-01-01T00:00:00Z.
    ///
    /// @param validTo the agreement's exclusive end, `null` for an open-ended agreement with no end date
    private static Tariff createTariff(@Nullable String validTo) {
        return Tariff.builder()
                     .setTariffCode("E-1R-AGILE-23-12-06-A")
                     .setValidFrom(Instant.parse("2024-01-01T00:00:00Z"))
                     .setValidTo(mapIfNotNull(validTo, Instant::parse))
                     .build();
    }
}
