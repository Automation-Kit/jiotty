package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import net.yudichev.jiotty.common.time.calendar.CalendarEventIds;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarTest {

    @Test
    void toCalendarEvent_identifiesTheEventByItsGoogleId() {
        // A singleEvents listing names each occurrence of a recurring entry <recurringEventId>_<originalStartTime>, so this identifies the occurrence.
        String googleId = "6dbjq5ebvmk5g8ki5c2j9lg9bs_20240211T140000Z";

        assertThat(GoogleCalendar.toCalendarEvent(newEvent().setId(googleId)))
                .hasValueSatisfying(calendarEvent -> assertThat(calendarEvent.id()).isEqualTo(googleId));
    }

    @Test
    void toCalendarEvent_fallsBackToAContentDerivedIdWhenTheEventHasNoId() {
        String expectedId = CalendarEventIds.createContentDerivedId("Swimming", "Leisure Centre");

        assertThat(GoogleCalendar.toCalendarEvent(newEvent()))
                .hasValueSatisfying(calendarEvent -> assertThat(calendarEvent.id()).isEqualTo(expectedId)
                                                                                   .doesNotContain("Swimming")
                                                                                   .doesNotContain("Leisure Centre"));
    }

    @Test
    void toCalendarEvent_mapsAnUntitledEventToAnEmptySummary() {
        String expectedId = CalendarEventIds.createContentDerivedId("", "Leisure Centre");

        assertThat(GoogleCalendar.toCalendarEvent(newEvent().setSummary(null)))
                .hasValueSatisfying(calendarEvent -> {
                    assertThat(calendarEvent.summary()).isEmpty();
                    assertThat(calendarEvent.id()).isEqualTo(expectedId);
                });
    }

    @Test
    void toCalendarEvent_skipsAnEventWithNoUsableTimes() {
        var event = newEvent().setEnd(new EventDateTime());

        assertThat(GoogleCalendar.toCalendarEvent(event)).isEmpty();
    }

    private static Event newEvent() {
        return new Event()
                .setSummary("Swimming")
                .setLocation("Leisure Centre")
                .setStart(new EventDateTime().setDateTime(new DateTime(Instant.parse("2024-02-11T14:00:00Z").toEpochMilli())))
                .setEnd(new EventDateTime().setDateTime(new DateTime(Instant.parse("2024-02-11T15:00:00Z").toEpochMilli())));
    }
}
