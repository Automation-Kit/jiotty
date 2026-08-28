package net.yudichev.jiotty.persistence.varstore;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Stream;

import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// The serialised form of a stored temporal value is both what the GDPR Art. 15 archive shows a data subject and what another build has to read back.
final class VarStoreJsonTest {
    static Stream<Arguments> readsBothTheNumericAndTheStringForm() {
        return Stream.of(arguments(LocalDate.class, "[2026,8,27]", LocalDate.of(2026, 8, 27)),
                         arguments(LocalDate.class, "\"2026-08-27\"", LocalDate.of(2026, 8, 27)),
                         arguments(Instant.class, "1787832000.000000000", Instant.parse("2026-08-27T12:00:00Z")),
                         arguments(Instant.class, "\"2026-08-27T12:00:00Z\"", Instant.parse("2026-08-27T12:00:00Z")),
                         arguments(Duration.class, "12.500000000", Duration.ofMillis(12_500)),
                         arguments(Duration.class, "\"PT12.5S\"", Duration.ofMillis(12_500)));
    }

    /// A row holding the numeric form must load, and so must one holding the string form — pinned so a Jackson upgrade cannot drop either silently.
    @ParameterizedTest
    @MethodSource
    void readsBothTheNumericAndTheStringForm(Class<?> type, String storedJson, Object expected) {
        assertThat(read(VarStoreJson.COMPACT, storedJson, type)).isEqualTo(expected);
        assertThat(read(VarStoreJson.INDENTED, storedJson, type)).isEqualTo(expected);
    }

    static Stream<Arguments> writesTemporalValuesAsStrings() {
        return Stream.of(arguments(LocalDate.of(2026, 8, 27), "\"2026-08-27\""),
                         arguments(Instant.parse("2026-08-27T12:00:00Z"), "\"2026-08-27T12:00:00Z\""),
                         arguments(Duration.ofMillis(12_500), "\"PT12.5S\""));
    }

    /// The Art. 15 archive reports the stored form verbatim, so an intelligible value has to be what gets stored.
    @ParameterizedTest
    @MethodSource
    void writesTemporalValuesAsStrings(Object value, String expectedJson) {
        assertThat(write(VarStoreJson.COMPACT, value)).isEqualTo(expectedJson);
        assertThat(write(VarStoreJson.INDENTED, value)).isEqualTo(expectedJson);
    }

    /// One configuration across every store, so a value encoded by one reads identically from another.
    @Test
    void theCompactAndIndentedMappersDifferInWhitespaceAlone() {
        var value = new Object[]{LocalDate.of(2026, 8, 27)};

        assertThat(write(VarStoreJson.INDENTED, value).replaceAll("\\s", "")).isEqualTo(write(VarStoreJson.COMPACT, value));
    }

    private static Object read(ObjectMapper mapper, String json, Class<?> type) {
        return getAsUnchecked(() -> mapper.readValue(json, type));
    }

    private static String write(ObjectMapper mapper, Object value) {
        return getAsUnchecked(() -> mapper.writeValueAsString(value));
    }
}
