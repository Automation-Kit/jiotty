package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.base.Supplier;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Uid;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.time.calendar.CalendarEventIds;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class IcloudCalendarTest {
    @Mock
    private SchedulingExecutor executor;
    @Mock
    private Supplier<CloseableHttpClient> httpClientFactory;

    @Test
    void toStringRedactsTheHrefAndTheCalendarName() {
        // A CalDAV href opens with the iCloud account id, and the name is a user-authored calendar title.
        var calendar = new IcloudCalendar("https://caldav.icloud.com/",
                                          "/1051121623/calendars/27A6139E-FFFD-4C0F-9DCC-B914B9CB04E7/",
                                          "Family Holidays",
                                          executor,
                                          httpClientFactory);

        assertThat(calendar).asString()
                            .doesNotContain("1051121623")
                            .doesNotContain("Family Holidays")
                            .isEqualTo("IcloudCalendar{id='/10…', name='Fam…'}");
    }

    @Test
    void toCalendarEvent_identifiesTheEventByItsUid() {
        var event = newEvent();
        event.add(new Uid("27A6139E-FFFD-4C0F-9DCC-B914B9CB04E7"));

        assertThat(IcloudCalendar.toCalendarEvent(event).id()).isEqualTo("27A6139E-FFFD-4C0F-9DCC-B914B9CB04E7");
    }

    @Test
    void toCalendarEvent_identifiesOneOccurrenceOfASeriesByItsRecurrenceIdToo() {
        // An expanded instance carries the series' UID, so the UID alone would make every occurrence of a weekly meeting the same event.
        var firstOccurrence = newEvent();
        firstOccurrence.add(new Uid("series-uid"));
        firstOccurrence.add(new RecurrenceId<>(Instant.parse("2024-02-11T14:00:00Z")));
        var secondOccurrence = newEvent();
        secondOccurrence.add(new Uid("series-uid"));
        secondOccurrence.add(new RecurrenceId<>(Instant.parse("2024-02-18T14:00:00Z")));

        assertThat(IcloudCalendar.toCalendarEvent(firstOccurrence).id())
                .isEqualTo("series-uid/20240211T140000Z")
                .isNotEqualTo(IcloudCalendar.toCalendarEvent(secondOccurrence).id());
    }

    @Test
    void toCalendarEvent_fallsBackToAContentDerivedIdWhenTheEventHasNoUid() {
        var event = newEvent();

        assertThat(IcloudCalendar.toCalendarEvent(event).id())
                .isEqualTo(CalendarEventIds.createContentDerivedId("Swimming", "Leisure Centre"))
                .doesNotContain("Swimming")
                .doesNotContain("Leisure Centre");
    }

    @Test
    void accessorsKeepTheValuesTheRedactedRenderingHides() {
        var calendar = new IcloudCalendar("https://caldav.icloud.com/", "/1051121623/calendars/x/", "Family Holidays", executor, httpClientFactory);

        assertThat(calendar.id()).isEqualTo("/1051121623/calendars/x/");
        assertThat(calendar.name()).isEqualTo("Family Holidays");
    }

    private static VEvent newEvent() {
        var event = new VEvent(Instant.parse("2024-02-11T14:00:00Z"), Instant.parse("2024-02-11T15:00:00Z"), "Swimming");
        event.add(new Location("Leisure Centre"));
        return event;
    }
}
