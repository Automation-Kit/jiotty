package net.yudichev.jiotty.connector.octopusenergy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import net.yudichev.jiotty.common.lang.PackagePrivateImmutablesStyle;
import org.immutables.value.Value.Immutable;

import java.util.List;
import java.util.Optional;

/// One page of a paginated `/products/` response. Pagination is an implementation detail of [OctopusEnergy#listProducts] — the generated [ProductsPage] is
/// package-private (via [PackagePrivateImmutablesStyle]) so external callers cannot reach it; they see only the assembled [List] of [Product].
@Immutable
@PackagePrivateImmutablesStyle
@JsonDeserialize
@JsonIgnoreProperties(ignoreUnknown = true)
interface BaseProductsPage {
    @JsonProperty("next")
    Optional<String> nextUrl();

    @JsonProperty("results")
    List<Product> results();
}
