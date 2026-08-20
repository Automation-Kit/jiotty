package net.yudichev.jiotty.common.time.calendar;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarEventTest {

    @Test
    void toStringNamesTheEntryByItsIdAndRedactsWhatTheUserWrote() {
        var event = CalendarEvent.builder()
                                 .setId("6dbjq5ebvmk5g8ki5c2j9lg9bs")
                                 .setSummary("Oncology appointment")
                                 .setLocation("13 Harley Street")
                                 .setStart(Instant.parse("2024-02-11T14:00:00Z"))
                                 .setEnd(Instant.parse("2024-02-11T15:00:00Z"))
                                 .build();

        assertThat(event).asString()
                         .contains("6dbjq5ebvmk5g8ki5c2j9lg9bs")
                         .doesNotContain("Oncology appointment")
                         .doesNotContain("13 Harley Street");
    }
}
