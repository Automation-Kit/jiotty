package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value;

import java.time.Instant;
import java.util.Optional;

/// A tariff agreement over a half-open `[validFrom, validTo)` window.
@Value.Immutable
@PublicImmutablesStyle
@JsonDeserialize
interface BaseTariff {
    /// The Octopus tariff code (e.g. `E-1R-AGILE-23-12-06-A`); the trailing letter is the supply region.
    @JsonProperty("tariff_code")
    String tariffCode();

    /// The instant the agreement starts, inclusive.
    @JsonProperty("valid_from")
    Instant validFrom();

    /// The instant the agreement ends, exclusive; empty when Octopus sends `"valid_to": null`, meaning that no end date is set yet. Octopus may still close or
    /// replace such an agreement, as it does with the variable tariff that follows a fixed-term one.
    @JsonProperty("valid_to")
    Optional<Instant> validTo();

    /// Whether `instant` falls in this agreement's half-open window `[validFrom, validTo)`, which an empty [#validTo()] leaves open-ended.
    default boolean covers(Instant instant) {
        Instant end = validTo().orElse(null);
        return !instant.isBefore(validFrom()) && (end == null || instant.isBefore(end));
    }

    /// Whether this agreement's half-open window overlaps `[from, to)`.
    default boolean overlaps(Instant from, Instant to) {
        Instant end = validTo().orElse(null);
        return validFrom().isBefore(to) && (end == null || end.isAfter(from));
    }
}
