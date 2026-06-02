package net.yudichev.jiotty.timeseriescache;

/// Three-way result of decoding a stored frame, so the read path can distinguish a usable value, a known-empty slot, and a row that must be discarded.
///
/// - [Present] — the frame decoded to a value.
/// - [Empty] — a tombstone frame: the slot is definitively empty (negative-cache hit), not recomputed.
/// - [Discard] — the row cannot be used and must be wiped (then the slot recomputes). [Discard#alarming] separates the *expected* case — a schema-version
/// mismatch after a deploy, which is routine and silent — from a genuinely *corrupt* row (bad magic, unknown format, truncated frame, or a payload that failed
/// to decode), which warrants an admin alert. [Discard#reason] is built from frame metadata only (magic / format id / version / length / failure category) and
/// never contains payload bytes, so it is safe to surface in a (PII-free) alert.
// T is unused by Empty/Discard themselves, but is required so they remain subtypes of DecodeOutcome<T> and the read-path switch can stay generic.
@SuppressWarnings("unused")
sealed interface DecodeOutcome<T> {
    record Present<T>(T value) implements DecodeOutcome<T> {}

    /// A tombstone decode carries no data, so a single shared instance (via [#instance]) serves every empty result rather than allocating one per decoded row.
    final class Empty<T> implements DecodeOutcome<T> {
        private static final Empty<?> INSTANCE = new Empty<>();

        private Empty() {
        }

        @SuppressWarnings("unchecked") // the singleton holds no T-typed state, so reusing it as any DecodeOutcome<T> is sound
        static <T> Empty<T> instance() {
            return (Empty<T>) INSTANCE;
        }
    }

    record Discard<T>(boolean alarming, String reason) implements DecodeOutcome<T> {}
}
