package net.yudichev.jiotty.common.geo;

import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

public record LatLon(double lat, double lon) implements StringFormattable {
    @Override
    public String toString() {
        return toString(32);
    }

    @Override
    public void formatTo(Appendable appendable) {
        Append.to(appendable, '{');
        Append.to(appendable, lat);
        Append.to(appendable, ',');
        Append.to(appendable, lon);
        Append.to(appendable, '}');
    }
}
