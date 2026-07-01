package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.StringFormattable;

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
    /// **Schema versioning.** Every cached value records the schema version it was written under. When a value is read back under a version different from the
    /// stream's current one — because the version was bumped after a change to the type's shape that older values can no longer satisfy — it is treated as
    /// absent and recomputed rather than reinterpreted. A version bump therefore retires values of the old shape instead of mis-decoding them; there is no
    /// migration. Bump it whenever the type changes in a way that an older cached value cannot satisfy (a renamed/removed/retyped field, or a change to how the
    /// stream's values are computed/selected that makes old values wrong).
    ///
    /// This overload takes the version **explicitly** — use it for value types you cannot annotate, e.g. third-party DTOs whose module must not depend on this
    /// one. For types you own, prefer the [#defineStream(String, Scope, Resolution, TypeToken, Function)] overload, which reads the version from the type's
    /// [CacheSchemaVersion]. There is no implicit default: a value type must declare its version one way or the other.
    ///
    /// @throws IllegalArgumentException if `streamId` is blank; if `schemaVersion` is outside `[1, 65535]`; or if re-registration of the same `(streamId,
    /// scope)` specifies a different `resolution` or `type` than the existing registration
    <T> TimeSeriesStream<T> defineStream(String streamId,
                                         Scope scope,
                                         Resolution resolution,
                                         TypeToken<T> type,
                                         int schemaVersion,
                                         Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation);

    /// Registers a stream whose value `type` declares its schema version via [CacheSchemaVersion]. Equivalent to the explicit-version overload with the
    /// type's annotated version. See that overload and [#defineStream(String, Scope, Resolution, TypeToken, int, Function)] for the versioning contract.
    ///
    /// @throws IllegalArgumentException if `type` is not annotated with [CacheSchemaVersion] (there is no implicit default — annotate the type, or use the
    /// explicit-version overload), plus the conditions of the explicit-version overload
    default <T> TimeSeriesStream<T> defineStream(String streamId,
                                                 Scope scope,
                                                 Resolution resolution,
                                                 TypeToken<T> type,
                                                 Function<SortedSet<Instant>, CompletableFuture<Map<Instant, Optional<T>>>> slotsComputation) {
        return defineStream(streamId, scope, resolution, type, CacheSchemaVersions.resolve(type), slotsComputation);
    }

    /// Removes every cached entry whose [Scope] matches the argument exactly, and evicts every registered stream handle with the same scope. Use when the
    /// underlying scope-bearing entity goes away — e.g. a deleted user account ([Scope.User]), a decommissioned region ([Scope.Region]), or a flush of all
    /// global rows ([Scope.Global]). Operates across all streamIds.
    CompletableFuture<Integer> deleteAllForScope(Scope scope);

    /// Removes every cached entry for the named stream (across all scopes) and evicts every registered stream handle with that streamId. Used when a stream is
    /// decommissioned.
    CompletableFuture<Integer> deleteAllForStream(String streamId);

    /// Removes every cached entry, across all scopes and all streams, whose slot start is strictly before `cutoffExclusive`. This is the storage-management
    /// purge for a retention horizon. Unlike [#deleteAllForScope] / [#deleteAllForStream] it does NOT evict any stream handle — the streams stay live; a
    /// purged past slot simply recomputes through its `slotsComputation` if read again. Returns the number of rows deleted.
    CompletableFuture<Integer> deleteOlderThan(Instant cutoffExclusive);

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

        record User(String userId) implements Scope, StringFormattable {
            public User {
                if (userId == null || userId.isBlank()) {
                    throw new IllegalArgumentException("userId must be non-blank");
                }
            }

            @Override
            public String toString() {
                return toString(32);
            }

            @Override
            public void formatTo(Appendable appendable) {
                Append.to(appendable, "user(");
                Append.to(appendable, userId);
                Append.to(appendable, ')');
            }
        }

        record Region(String code) implements Scope, StringFormattable {
            public Region {
                if (code == null || code.isBlank()) {
                    throw new IllegalArgumentException("code must be non-blank");
                }
            }

            @Override
            public String toString() {
                return toString(16);
            }

            @Override
            public void formatTo(Appendable appendable) {
                Append.to(appendable, "region(");
                Append.to(appendable, code);
                Append.to(appendable, ')');
            }
        }
    }
}
