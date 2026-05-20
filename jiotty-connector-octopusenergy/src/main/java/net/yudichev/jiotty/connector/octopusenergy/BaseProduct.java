package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PublicImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/// Summary entry from `/products/`. The full payload at `/products/{code}/` is mapped by [BaseProductDetails].
@Immutable
@PublicImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseProduct {
    String code();

    @JsonProperty("display_name")
    String displayName();

    /// Longer marketing name (e.g. "Octopus Agile December 2023 v1"). Surfaced alongside [#displayName] because some product classifiers concatenate the two
    /// and search for keywords that appear in only one (e.g. the version-suffix variants of "Intelligent Octopus Go" carry critical "Go" / "EV Saver" tokens in
    /// `fullName` that aren't in `displayName`).
    @JsonProperty("full_name")
    String fullName();

    String brand();

    @JsonProperty("available_from")
    Instant availableFrom();

    /// `null` in the JSON when the product is still currently available.
    @JsonProperty("available_to")
    Optional<Instant> availableTo();

    List<ProductLink> links();
}
