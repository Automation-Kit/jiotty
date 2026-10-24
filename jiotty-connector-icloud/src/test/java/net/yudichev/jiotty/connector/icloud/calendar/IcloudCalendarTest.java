package net.yudichev.jiotty.connector.icloud.calendar;

import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Uid;
import net.yudichev.jiotty.common.async.ProgrammableClock;
import net.yudichev.jiotty.common.time.calendar.CalendarEventIds;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.ACCOUNT_ID;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.CALENDAR_HREF;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.CALENDAR_TITLE;
import static net.yudichev.jiotty.connector.icloud.calendar.CalDavFixtures.HOME;
import static org.assertj.core.api.Assertions.assertThat;

class IcloudCalendarTest {
    private static final Supplier<CompletableFuture<CalDavClient>> FAILING_CLIENT_SUPPLIER = () -> {
        throw new AssertionError("no request expected");
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
        IcloudCalendar calendar = newCalendar(CALENDAR_HREF, CALENDAR_TITLE);

        assertThat(calendar.id()).isEqualTo(CALENDAR_HREF);
        assertThat(calendar.name()).isEqualTo(CALENDAR_TITLE);
    }

    private static IcloudCalendar newCalendar(String href, String name) {
        return new IcloudCalendar(href,
                                  checkNotNull(HOME.resolve(href)),
                                  name,
                                  FAILING_CLIENT_SUPPLIER,
                                  new ProgrammableClock().createSingleThreadedSchedulingExecutor("test"),
                                  () -> true,
                                  "test-user");
    }

    private static VEvent newEvent() {
        var event = new VEvent(Instant.parse("2024-02-11T14:00:00Z"), Instant.parse("2024-02-11T15:00:00Z"), "Swimming");
        event.add(new Location("Leisure Centre"));
        return event;
    }
}
