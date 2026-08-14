package net.yudichev.jiotty.common.geo;

import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.security.LogRedaction;

import static net.yudichev.jiotty.common.security.LogRedaction.appendRedacted;

/// A point on the surface of the Earth.
///
/// @implNote string representation is coarsened, via [LogRedaction#appendRedacted(Appendable, LatLon)].
public record LatLon(double lat, double lon) implements StringFormattable {
    @Override
    public String toString() {
        return toString(32);
    }

    @Override
    public void formatTo(Appendable appendable) {
        appendRedacted(appendable, this);
    }
}
