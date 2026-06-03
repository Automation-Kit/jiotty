package net.yudichev.jiotty.energy;

import com.google.common.collect.ImmutableMap;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.connector.octopusenergy.ConsumptionRow;
import net.yudichev.jiotty.connector.octopusenergy.Product;
import net.yudichev.jiotty.connector.octopusenergy.ProductDetails;
import net.yudichev.jiotty.connector.octopusenergy.StandardUnitRate;
import net.yudichev.jiotty.connector.octopusenergy.StandingCharge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/// A user's energy provider: everything [EnergyPriceService] offers, plus the account-level concerns bound to the same credentials — authentication state and
/// the account's metering/tariff details — plus read-only queries over the provider's time-series data and product catalogue.
///
/// One instance represents one account with one provider.
///
/// @implSpec The `query*` methods over time-series data ([#queryConsumption], [#queryRates], [#queryStandingCharges]) are backed by a slot-level cache:
/// historical slots, once fetched, are served from cache forever after, so repeated or overlapping queries only ever hit the provider for slots not yet
/// cached. Implementations MUST NOT expose any path that fetches this data bypassing that cache.
public interface EnergyProviderService extends EnergyPriceService {
    /// Subscribes to authentication-state changes for the account's credentials. The current state is delivered to the new subscriber immediately. The
    /// returned [Closeable] cancels the subscription.
    Closeable subscribeToAuthState(Consumer<AuthState> consumer);

    /// Subscribes to the account's details ([AccountFetchResult]). The latest result is delivered to the new subscriber immediately; before any result is
    /// available, nothing is delivered. The returned [Closeable] cancels the subscription.
    Closeable subscribeToAccountDetails(Consumer<AccountFetchResult> consumer);

    /// Returns the account's half-hourly electricity consumption over `[from, to]` for one meter, keyed by slot start in chronological order. Slots the
    /// provider has no reading for are absent from the map.
    ///
    /// @param userId      caller's stable user id, used only as the cache scope key for this account's rows (the credentials are the implementation's own)
    /// @param mpan        the meter point's MPAN
    /// @param meterSerial the meter's serial number
    /// @param from        first slot start (inclusive)
    /// @param to          last slot start (inclusive)
    CompletableFuture<ImmutableMap<Instant, ConsumptionRow>> queryConsumption(String userId, String mpan, String meterSerial, Instant from, Instant to);

    /// Returns the half-hourly standard unit rates for the given tariff over `[from, to]`, keyed by slot start in chronological order. Each slot maps to the
    /// rate whose validity window covers it; slots with no published rate are absent.
    ///
    /// @param productCode the Octopus product code (e.g. `AGILE-23-12-06`)
    /// @param tariffCode  the region-specific tariff code (e.g. `E-1R-AGILE-23-12-06-A`); its trailing letter is the supply region
    CompletableFuture<ImmutableMap<Instant, StandardUnitRate>> queryRates(String productCode, String tariffCode, Instant from, Instant to);

    /// Returns the daily standing charges for the given tariff over `[from, to]`, keyed by day start in chronological order. Each day maps to the charge whose
    /// validity window covers it; days with no published charge are absent.
    CompletableFuture<ImmutableMap<Instant, StandingCharge>> queryStandingCharges(String productCode, String tariffCode, Instant from, Instant to);

    /// Returns the products the provider lists as available at `availableAt`.
    CompletableFuture<List<Product>> queryProducts(Instant availableAt);

    /// Returns the full per-region tariff details for the product with the given code.
    CompletableFuture<ProductDetails> queryProductDetails(String code);

    /// Returns the start instant of the latest half-hourly consumption slot the provider has *published* for one meter, or empty if none in the recent probe
    /// window. This is the provider's consumption "publication frontier": smart-meter consumption is published with a variable, undocumented delay (hours to
    /// 48 h+), so a calendar day being over does not mean its consumption is available yet. Callers use the frontier to decide which days are settled (fully
    /// published) and therefore safe to compute, cache, and negative-cache — never finalising a day the provider may still be filling in.
    ///
    /// @implSpec Unlike the `query*` methods, this MUST be served by a direct, **uncached** provider call: it determines *whether* a slot is final, which is
    /// the very precondition the slot cache relies on (caching the probe would tombstone not-yet-published slots — the bug this exists to prevent). It returns
    /// only the frontier instant, never consumption values, so it does not violate the "all consumption reads go through the cache" invariant for actual data.
    CompletableFuture<Optional<Instant>> latestConsumptionInstant(String mpan, String meterSerial);
}
