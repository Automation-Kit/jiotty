package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/// Cache of typed time-series streams. Per-stream typed access is via [TimeSeriesStream] handles obtained from [#defineStream].
///
/// Each stream's `(streamId, scope, resolution, type, slotsComputation)` are supplied to [#defineStream]; the returned handle then exposes only
/// [TimeSeriesStream#readRange]. Variant encoding (e.g. params hash, region) is done through the `streamId` string shape (e.g.
/// `octopus.rates:E-1R-AGILE-23-12-06-A`); the cache treats stream ids as opaque.
///
/// Scoping:
/// - [Scope.Global] — rows shared across all users (e.g. region-keyed Octopus tariff data).
/// - [Scope.User] — per-user rows.
/// - [Scope.Region] — per-region rows; the region code is the discriminator.
///
/// All operations are asynchronous; misses surface as absent entries in the composed map, not errors. A `slotsComputation` reports each requested slot in one
/// of three states: a present value ([Optional#of]) is cached and returned; an explicit empty ([Optional#empty]) is a *negative-cache tombstone* — the slot is
/// definitively empty, cached as such, and never recomputed; a slot absent from the returned map is left uncached and recomputed on the next read. Tombstoned
/// and absent slots are both excluded from the composed value map; they differ only in whether a later read recomputes them.
///
/// @implSpec Implementations MUST serialise and deserialise values idempotently using a single mapper configuration consistent across every method, so values
/// written by any caller round-trip back to the same POJO shape regardless of which call made the write.
public interface TimeSeriesCache {
    /// Registers a stream and returns its typed handle. Re-registering the same `(streamId, scope)` with the same `resolution` and same `type` returns the
    /// previously-registered handle (idempotent). The `slotsComputation` of a re-registration is silently ignored — lambdas are not comparable, so the first
    /// registration's lambda stays in effect.
    ///
    /// @throws IllegalArgumentException if `streamId` is blank, or if re-registration of the same `(streamId, scope)` specifies a different `resolution` or
    /// `type` than the existing registration
    <T> TimeSeriesStream<T> defineStream(String streamId,
                                         Scope scope,
                                         Resolution resolution,
                                         TypeToken<T> type,
                                         Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation);

    /// Removes every cached entry whose [Scope] matches the argument exactly, and evicts every registered stream handle with the same scope. Use when the
    /// underlying scope-bearing entity goes away — e.g. a deleted user account ([Scope.User]), a decommissioned region ([Scope.Region]), or a flush of all
    /// global rows ([Scope.Global]). Operates across all streamIds.
    CompletableFuture<Integer> deleteAllForScope(Scope scope);

    /// Removes every cached entry for the named stream (across all scopes) and evicts every registered stream handle with that streamId. Used when a stream is
    /// decommissioned.
    CompletableFuture<Integer> deleteAllForStream(String streamId);

    sealed interface Scope permits Scope.Global, Scope.User, Scope.Region {
        /// Returns the singleton global scope.
        static Scope global() {
            return Global.INSTANCE;
        }

        /// Convenience factory for a user-scoped binding.
        static Scope user(String userId) {
            return new User(userId);
        }

        /// Convenience factory for a region-scoped binding (e.g. an Octopus tariff region code).
        static Scope region(String code) {
            return new Region(code);
        }

        final class Global implements Scope {
            static final Global INSTANCE = new Global();

            private Global() {
            }

            @Override
            public String toString() {
                return "global";
            }
        }

        record User(String userId) implements Scope {
            public User {
                if (userId == null || userId.isBlank()) {
                    throw new IllegalArgumentException("userId must be non-blank");
                }
            }

            @Override
            public String toString() {
                return "user(" + userId + ")";
            }
        }

        record Region(String code) implements Scope {
            public Region {
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("code must be non-blank");
                }
            }

            @Override
            public String toString() {
                return "region(" + code + ")";
            }
        }
    }
}
