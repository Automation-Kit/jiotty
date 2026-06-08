package net.yudichev.jiotty.common.time.calendar;

import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;

import java.time.temporal.Temporal;
import java.util.Optional;

import static net.yudichev.jiotty.common.security.LogRedaction.redacted;
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
        var builder = new StringBuilder(64).append("CalendarEvent{");
        redacted(summary()).formatTo(builder);
        builder.append(' ').append(start()).append("...").append(end());
        location().ifPresent(loc -> {
            builder.append(" @ ");
            redacted(loc).formatTo(builder);
        });
        description().ifPresent(desc -> {
            builder.append(" (");
            redacted(desc).formatTo(builder);
            builder.append(')');
        });
        return builder.append('}').toString();
    }
}
