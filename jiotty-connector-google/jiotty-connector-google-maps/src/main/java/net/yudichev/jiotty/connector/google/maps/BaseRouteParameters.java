package net.yudichev.jiotty.connector.google.maps;

import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;
import org.immutables.value.Value.Redacted;

import java.time.Instant;
import java.util.Optional;

@Immutable
@PublicImmutablesStyle
interface BaseRouteParameters {
    @Redacted
    Either<String, LatLon> originLocation();

    @Redacted
    Either<String, LatLon> destinationLocation();

    Optional<Instant> departureTime();

    Optional<Instant> arrivalTime();
}
