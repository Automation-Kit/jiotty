package net.yudichev.jiotty.connector.octopusenergy;

import net.yudichev.jiotty.common.lang.Closeable;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/// Octopus connector entry point. Splits into two sub-services that reflect the API's natural division of labour:
///
/// - [#region] returns a per-region handle for data shared across all users in that region (rates, standing charges).
/// - [#account] returns a per-credentials handle for data specific to one user's account (consumption, meter point details).
///
/// Both handles are [Closeable] — callers own their lifecycle. The same key (region letter or `(accountId, apiKey)` pair) returns the same handle on repeat
/// invocations until that handle is closed; a subsequent call after close returns a fresh handle.
///
/// In addition to the per-handle sub-services, this interface exposes endpoints that are not bound to either region or account — product discovery. They are
/// open endpoints (no authentication) and return the same data for every caller, so they live at the top level rather than under one of the sub-services.
public interface OctopusEnergy {
    /// Returns the per-region handle for the given Octopus tariff region letter (A–P minus I/O — see [MpanRegionResolver#isValidRegion]).
    ///
    /// @throws IllegalArgumentException if `regionLetter` is not one of the known region letters
    OctopusRegionService region(char regionLetter);

    /// Returns the per-account handle for the given credentials pair.
    OctopusAccountService account(String accountId, String apiKey);

    /// Returns every product available at the given instant. Pagination is handled internally; the returned list is the complete server-side result in source
    /// order.
    CompletableFuture<List<Product>> listProducts(Instant availableAt);

    /// Returns the full details of one product, including the tariff matrix keyed by region and payment method.
    CompletableFuture<ProductDetails> getProductDetails(String code);
}
