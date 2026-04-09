package net.yudichev.jiotty.common.time.calendar;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;

import java.time.temporal.Temporal;
import java.util.Optional;

import static org.immutables.value.Value.Immutable;

@Immutable
@PublicImmutablesStyle
abstract class BaseCalendarEvent {
    public abstract Temporal start();

    public abstract Temporal end();

    public abstract String summary();

    public abstract Optional<String> description();

    public abstract Optional<String> location();

    @Override
    public String toString() {
        var builder = new StringBuilder(512)
                .append("CalendarEvent{")
                .append(summary()).append(' ')
                .append(start()).append("...").append(end());
        location().ifPresent(loc -> builder.append(" @ ").append(loc.replace('\n', ',')));
        description().ifPresent(desc -> builder.append(" (").append(desc).append(')'));
        return builder.append('}').toString();
    }
}
