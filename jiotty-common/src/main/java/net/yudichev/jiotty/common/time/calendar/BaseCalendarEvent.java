package net.yudichev.jiotty.common.time.calendar;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import net.yudichev.jiotty.common.lang.StringFormattable;

import java.time.temporal.Temporal;
import java.util.Optional;

import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;
import static org.immutables.value.Value.Immutable;

@Immutable
@PublicImmutablesStyle
abstract class BaseCalendarEvent implements StringFormattable {
    /// Identifies the entry within its calendar, and one occurrence of a recurring entry within the series. Providers that expose no id of their own get one
    /// from [CalendarEventIds#createContentDerivedId].
    public abstract String id();

    public abstract Temporal start();

    public abstract Temporal end();

    public abstract String summary();

    public abstract Optional<String> description();

    public abstract Optional<String> location();

    @Override
    public String toString() {
        return toString(64);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "CalendarEvent{");
        Append.to(appendable, id());
        Append.to(appendable, ' ');
        appendRedacted(appendable, summary());
        Append.to(appendable, ' ');
        Append.to(appendable, start());
        Append.to(appendable, "...");
        Append.to(appendable, end());
        location().ifPresent(loc -> {
            Append.to(appendable, " @ ");
            appendRedacted(appendable, loc);
        });
        description().ifPresent(desc -> {
            Append.to(appendable, " (");
            appendRedacted(appendable, desc);
            Append.to(appendable, ')');
        });
        Append.to(appendable, '}');
    }
}
