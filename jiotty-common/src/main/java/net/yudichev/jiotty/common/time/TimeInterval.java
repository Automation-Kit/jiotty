package net.yudichev.jiotty.common.time;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

public final class TimeInterval implements StringFormattable {
    private final Instant start;
    private final Instant end;
    private Duration duration;

    public TimeInterval(Instant start, Instant end) {
        checkArgument(!start.isAfter(end), "start %s must not be after end %s", start, end);
        this.start = start;
        this.end = end;
    }

    @JsonProperty
    public Instant start() {
        return start;
    }

    @JsonProperty
    public Instant end() {
        return end;
    }

    public boolean intersectsWith(TimeInterval anotherInterval) {
        return start.isBefore(anotherInterval.end) && end.isAfter(anotherInterval.start);
    }

    public boolean contains(TimeInterval anotherInterval) {
        return !anotherInterval.start.isBefore(start) && !anotherInterval.end.isAfter(end);
    }

    public boolean isWhollyAfter(TimeInterval anotherInterval) {
        return !start.isBefore(anotherInterval.end);
    }

    public List<TimeInterval> minus(TimeInterval anotherInterval) {
        if (!intersectsWith(anotherInterval)) {
            return List.of(this);
        }
        if (anotherInterval.contains(this)) {
            return List.of();
        }
        if (!anotherInterval.start.isAfter(start)) {
            return List.of(new TimeInterval(anotherInterval.end, end));
        }
        if (!anotherInterval.end.isBefore(end)) {
            return List.of(new TimeInterval(start, anotherInterval.start));
        }
        return List.of(new TimeInterval(start, anotherInterval.start), new TimeInterval(anotherInterval.end, end));
    }

    public boolean contains(Instant instant) {
        return instant.compareTo(start) >= 0 && instant.compareTo(end) < 0;
    }

    public Duration duration() {
        if (duration == null) {
            duration = Duration.between(start, end);
        }
        return duration;
    }

    @JsonIgnore
    public boolean isEmpty() {
        return start.equals(end);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TimeInterval another && start.equals(another.start) && end.equals(another.end);
    }

    @Override
    public int hashCode() {
        int result = start.hashCode();
        result = 31 * result + end.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return toString(64);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, '[');
        Append.to(appendable, start);
        Append.to(appendable, "...");
        Append.to(appendable, end);
        Append.to(appendable, ']');
    }
}
