package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static net.yudichev.jiotty.user.ui.HistoryDisplayableDto.Format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class HistoryDisplayableDtoTest {
    private static final Instant TIME = Instant.parse("2026-07-02T18:00:00Z");

    static Stream<Arguments> toStringRedactsKeysAndPlainValuesButLetsFormattableValuesRedactThemselves() {
        StringFormattable formattableValue = appendable -> Append.to(appendable, "CarChargeRecordable[carId=VIN…]");
        return Stream.of(
                // a formattable value renders itself (only its own PII redacted); a plain string value is redacted; the group key is redacted
                arguments(new HistoryDisplayableDto(ImmutableMap.of("History", List.of(
                                  new HistoryDisplayableDto.Entry(TIME, Format.OBJECT, formattableValue),
                                  new HistoryDisplayableDto.Entry(TIME, Format.PLAIN_TEXT, "charging started")))),
                          "HistoryDisplayableDto[groups={His…=["
                          + "Entry[time=2026-07-02T18:00:00Z, format=OBJECT, value=CarChargeRecordable[carId=VIN…]], "
                          + "Entry[time=2026-07-02T18:00:00Z, format=PLAIN_TEXT, value=cha…]]}]"),
                // a null value renders as null rather than being redacted
                arguments(new HistoryDisplayableDto(ImmutableMap.of("History", List.of(
                                  new HistoryDisplayableDto.Entry(TIME, Format.OBJECT, null)))),
                          "HistoryDisplayableDto[groups={His…=[Entry[time=2026-07-02T18:00:00Z, format=OBJECT, value=null]]}]"));
    }

    @ParameterizedTest
    @MethodSource
    void toStringRedactsKeysAndPlainValuesButLetsFormattableValuesRedactThemselves(HistoryDisplayableDto dto, String expected) {
        assertThat(dto.toString()).isEqualTo(expected);
    }
}
