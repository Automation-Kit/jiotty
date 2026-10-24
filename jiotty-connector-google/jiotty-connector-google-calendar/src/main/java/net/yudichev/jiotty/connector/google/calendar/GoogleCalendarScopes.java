package net.yudichev.jiotty.connector.google.calendar;

import com.google.common.collect.ImmutableSet;

import java.util.Set;

public final class GoogleCalendarScopes {
    /// Read-only access to the user's calendar list, which `calendarList.list` needs.
    public static final String CALENDAR_LIST_READONLY = "https://www.googleapis.com/auth/calendar.calendarlist.readonly";
    /// Read-only access to the events of every calendar the user can see, which `events.list` needs.
    public static final String EVENTS_READONLY = "https://www.googleapis.com/auth/calendar.events.readonly";
    /// The scopes a user must grant for [GoogleCalendarModule]'s service to work, in the order they are requested.
    public static final Set<String> SCOPES = ImmutableSet.of(CALENDAR_LIST_READONLY, EVENTS_READONLY);

    private GoogleCalendarScopes() {
    }
}
