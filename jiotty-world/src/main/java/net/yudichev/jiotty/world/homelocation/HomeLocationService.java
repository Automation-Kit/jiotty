package net.yudichev.jiotty.world.homelocation;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.Closeable;

import java.util.function.Consumer;

public interface HomeLocationService {
    String OPTION_KEY = "homeLocation";

    Closeable addListener(Consumer<LatLon> listener);
}
