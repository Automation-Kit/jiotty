package net.yudichev.jiotty.connector.icloud.calendar;

import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Duration;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Uid;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.time.calendar.CalendarEvent;
import net.yudichev.jiotty.common.time.calendar.CalendarEventIds;
import net.yudichev.jiotty.connector.icloud.calendar.IcloudCalendar.CalendarDataReader;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.ACCOUNT_ID;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.CALENDAR_HREF;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.CALENDAR_TITLE;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.HOME;
import static org.assertj.core.api.Assertions.assertThat;

class IcloudCalendarTest {
    private static final Instant START = Instant.parse("2024-02-11T14:00:00Z");
    private static final Instant END = Instant.parse("2024-02-11T15:00:00Z");
    private static final CalendarDataReader FAILING_READER = new CalendarDataReader() {
        @Override
        public <T> CompletableFuture<T> read(HttpUrl calendarUrl,
                                             StringFormattable loggableUrl,
                                             Instant from,
                                             Instant to,
                                             Function<? super List<String>, ? extends T> parser) {
            throw new AssertionError("no request expected");
        }
    };

    @Test
    void toStringRedactsTheHrefAndTheCalendarName() {
        // A CalDAV href opens with the iCloud account id, and the name is a user-authored calendar title.
        IcloudCalendar calendar = newCalendar(CALENDAR_HREF, CALENDAR_TITLE);

        assertThat(calendar).asString()
                            .doesNotContain(ACCOUNT_ID)
                            .doesNotContain(CALENDAR_TITLE)
                            .isEqualTo("IcloudCalendar{id='/10…', name='Fam…'}");
    }

    @Test
    void toCalendarEvent_identifiesTheEventByItsUid() {
        var event = newEvent();
        event.add(new Uid("27A6139E-FFFD-4C0F-9DCC-B914B9CB04E7"));

        assertThat(IcloudCalendar.toCalendarEvent(event)).map(CalendarEvent::id).hasValue("27A6139E-FFFD-4C0F-9DCC-B914B9CB04E7");
    }

    @Test
    void toCalendarEvent_givesAnEventWithNoSummaryAnEmptyOne() {
        var event = new VEvent();
        event.add(new DtStart<>(START));
        event.add(new DtEnd<>(END));

        assertThat(IcloudCalendar.toCalendarEvent(event)).hasValueSatisfying(calendarEvent -> {
            assertThat(calendarEvent.summary()).isEmpty();
            assertThat(calendarEvent.start()).isEqualTo(START);
            assertThat(calendarEvent.end()).isEqualTo(END);
        });
    }

    @Test
    void toCalendarEvent_endsAnEventWithADurationAndNoEndAtItsStartPlusTheDuration() {
        var event = new VEvent();
        event.add(new DtStart<>(START));
        event.add(new Duration(START, END));

        assertThat(IcloudCalendar.toCalendarEvent(event)).hasValueSatisfying(calendarEvent -> {
            assertThat(calendarEvent.start()).isEqualTo(START);
            assertThat(calendarEvent.end()).isEqualTo(END);
        });
    }

    @Test
    void toCalendarEvent_isEmptyForAnEventWithNoStartOrNoEnd() {
        var eventWithNoEnd = new VEvent();
        eventWithNoEnd.add(new DtStart<>(START));
        var eventWithNoStart = new VEvent();
        eventWithNoStart.add(new DtEnd<>(END));

        assertThat(IcloudCalendar.toCalendarEvent(eventWithNoEnd)).isEmpty();
        assertThat(IcloudCalendar.toCalendarEvent(eventWithNoStart)).isEmpty();
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

        assertThat(IcloudCalendar.toCalendarEvent(firstOccurrence)).map(CalendarEvent::id).hasValue("series-uid/20240211T140000Z");
        assertThat(IcloudCalendar.toCalendarEvent(secondOccurrence)).map(CalendarEvent::id).hasValue("series-uid/20240218T140000Z");
    }

    @Test
    void toCalendarEvent_fallsBackToAContentDerivedIdWhenTheEventHasNoUid() {
        var event = newEvent();

        assertThat(IcloudCalendar.toCalendarEvent(event)).map(CalendarEvent::id).hasValueSatisfying(
                id -> assertThat(id).isEqualTo(CalendarEventIds.createContentDerivedId("Swimming", "Leisure Centre"))
                                    .doesNotContain("Swimming")
                                    .doesNotContain("Leisure Centre"));
    }

    @Test
    void accessorsKeepTheValuesTheRedactedRenderingHides() {
        IcloudCalendar calendar = newCalendar(CALENDAR_HREF, CALENDAR_TITLE);

        assertThat(calendar.id()).isEqualTo(CALENDAR_HREF);
        assertThat(calendar.name()).isEqualTo(CALENDAR_TITLE);
    }

    private static IcloudCalendar newCalendar(String href, String name) {
        return new IcloudCalendar(href, checkNotNull(HOME.resolve(href)), name, FAILING_READER, "test-user");
    }

    private static VEvent newEvent() {
        var event = new VEvent(START, END, "Swimming");
        event.add(new Location("Leisure Centre"));
        return event;
    }
}
