package net.yudichev.jiotty.connector.tesla.teslamatedb;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

import java.time.Duration;
import java.time.Instant;

import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;

@Immutable
@PublicImmutablesStyle
abstract class BaseHistoricalDrive {
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

    public String toString() {
        var sb = new StringBuilder(128).append("HistoricalDrive{").append(id()).append(',');
        appendRedacted(sb, startLocation());
        sb.append('@').append(startInstant()).append(' ').append(startSoC()).append("% -> ");
        appendRedacted(sb, endLocation());
        return sb.append('@').append(endInstant()).append(' ').append(endSoC()).append('%').toString();
    }
}
