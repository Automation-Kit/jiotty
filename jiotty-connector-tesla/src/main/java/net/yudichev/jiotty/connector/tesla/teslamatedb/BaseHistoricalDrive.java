package net.yudichev.jiotty.connector.tesla.teslamatedb;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import net.yudichev.jiotty.common.lang.StringFormattable;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

import java.time.Duration;
import java.time.Instant;

import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;

@Immutable
@PublicImmutablesStyle
abstract class BaseHistoricalDrive implements StringFormattable {
    public abstract long id();

    public abstract Instant startInstant();

    public abstract Instant endInstant();

    public abstract LatLon startLocation();

    public abstract LatLon endLocation();

    public abstract int startSoC();

    public abstract int endSoC();

    @Value.Derived
    public Duration duration() {
        return Duration.between(startInstant(), endInstant());
    }

    @Override
    public String toString() {
        return toString(128);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, "HistoricalDrive{");
        Append.to(appendable, id());
        Append.to(appendable, ',');
        appendRedacted(appendable, startLocation());
        Append.to(appendable, '@');
        Append.to(appendable, startInstant());
        Append.to(appendable, ' ');
        Append.to(appendable, startSoC());
        Append.to(appendable, "% -> ");
        appendRedacted(appendable, endLocation());
        Append.to(appendable, '@');
        Append.to(appendable, endInstant());
        Append.to(appendable, ' ');
        Append.to(appendable, endSoC());
        Append.to(appendable, '%');
    }
}
