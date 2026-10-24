package net.yudichev.jiotty.connector.google.calendar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarScopesTest {
    /// The literals are the grant users consent to, so a widened scope fails here even where every test comparing against the constants still passes.
    @Test
    void requestsOnlyTheReadOnlyCalendarListAndEventsScopes() {
        assertThat(GoogleCalendarScopes.SCOPES).containsExactly("https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                                                                "https://www.googleapis.com/auth/calendar.events.readonly");
    }
}
