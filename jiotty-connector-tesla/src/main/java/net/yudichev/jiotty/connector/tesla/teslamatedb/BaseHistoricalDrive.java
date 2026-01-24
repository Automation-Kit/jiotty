package net.yudichev.jiotty.connector.tesla.teslamatedb;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

import java.time.Duration;
import java.time.Instant;

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
        return "HistoricalDrive{"
               + id() + ','
               + startLocation() + '@' + startInstant() + ' ' + startSoC() + "% -> "
               + endLocation() + '@' + endInstant() + ' ' + endSoC() + '%';
    }
}
