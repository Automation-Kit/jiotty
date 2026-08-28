package net.yudichev.jiotty.persistence.varstore;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// TEMPORARY — delete with [LegacyTemporalFormatBackfill].
final class LegacyTemporalFormatBackfillTest {
    private static final Instant WHEN = Instant.parse("2026-08-27T12:00:00Z");

    static Stream<Arguments> judgesAStoredFormStaleOnlyWhereItsMeaningDiffers() {
        return Stream.of(
                // legacy temporal encodings — the rows the backfill exists for
                arguments("1787832000.000000000", WHEN, true),
                arguments("{\"since\":1787832000.000000000}", Map.of("since", WHEN), true),
                // already canonical
                arguments("\"2026-08-27T12:00:00Z\"", WHEN, false),
                arguments("{\"since\":\"2026-08-27T12:00:00Z\"}", Map.of("since", WHEN), false),
                // numeric values, where the stored node type and the one valueToTree builds differ
                arguments("42", 42L, false),
                arguments("1.5", 1.5f, false),
                arguments("1.50", new BigDecimal("1.50"), false));
    }

    /// Both sides are parsed before comparison, so indentation and Jackson's numeric node types cancel out; a freshly built tree compared against the stored
    /// one calls every `long`, `float` and [BigDecimal] row stale and rewrites it on every read.
    @ParameterizedTest
    @MethodSource
    void judgesAStoredFormStaleOnlyWhereItsMeaningDiffers(String storedJson, Object value, boolean stale) {
        assertThat(LegacyTemporalFormatBackfill.storedFormIsStale(VarStoreJson.COMPACT, storedJson, value)).isEqualTo(stale);
        assertThat(LegacyTemporalFormatBackfill.storedFormIsStale(VarStoreJson.INDENTED, storedJson, value)).isEqualTo(stale);
    }
}
