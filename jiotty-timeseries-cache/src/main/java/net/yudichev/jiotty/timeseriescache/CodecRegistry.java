package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import net.yudichev.jiotty.common.lang.AntiSpamLogger;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Per-cache-instance registry that owns the wire-framing rule and dispatches reads to the right [Codec] by per-row format id.
///
/// Wire layout of every framed value: `[MAGIC, formatId, versionHi, versionLo, ...payload]`. The single-byte [#MAGIC] is a parity check that detects "this
/// column was not written by our codec" (corruption, hand-inserted rows, unrelated bytes). The 1-byte `formatId` selects the codec. The 2-byte big-endian
/// unsigned `version` records the value type's [CacheSchemaVersion] at write time; a read whose expected version differs is discarded (and the slot
/// recomputes) rather than mis-decoded. A *tombstone* frame is the 2-byte `[MAGIC, FMT_TOMBSTONE]` (no version, no payload) — a negative-cache marker.
///
/// [#decode] classifies every frame as a [DecodeOutcome] (present value / empty tombstone / discard), so a single corrupt or stale row surfaces as a discard
/// the read path can wipe and recompute while the rest of the range read succeeds.
///
/// The registry holds one *write codec* (chosen at the cache-instance binding) plus the full set of known *read codecs*. Writes always go through the write
/// codec; reads dispatch to any codec whose `formatId` matches the per-row marker. This is what makes encoding-format changes incremental: switch the write
/// codec, keep both old and new in the read set, decode either format until the read traffic on the old format stops.
///
/// **Not thread-safe.** The registry holds a reusable [ByteArrayOutputStream] for the encode path and is intended to be accessed from a single thread (the
/// cache's `@UIExecutor`-style serialising executor). The reusable buffer grows to the encoded high-water mark and stays there — Jackson's generator writes
/// through it without per-row buffer allocation. Each value-encoding [#encode] call returns a fresh `byte[]` (via `toByteArray()`), so batched callers can
/// retain the result across encode cycles safely; an empty (tombstone) encode returns a shared immutable constant.
///
/// Format-id constants live in this class and nowhere else. Adding a new codec is: (1) implement [Codec]; (2) reserve a new `FMT_*` constant here; (3) include
/// the new codec in the registry's read set. The id space is forever — once assigned, a `formatId` keeps its meaning for the lifetime of the schema.
@SuppressWarnings("WeakerAccess") // public outer surface on a package-private class — see java-style "internal APIs on non-public types" rule
final class CodecRegistry {
    /// Sentinel that prefixes every framed value. ASCII `'J'`. Cheap parity check.
    public static final byte MAGIC = 0x4A;
    /// `0x00` — JSON-UTF-8. Diagnostic / inspection format. Not the production default.
    public static final byte FMT_JSON_UTF8 = 0x00;
    /// `0x01` — Smile. Production default. Compact binary; preserves JSON type model.
    public static final byte FMT_SMILE = 0x01;
    /// `0x02` — tombstone: a payload-less marker that a slot is *definitively empty* (negative cache entry). Has no [Codec]; [#decode] maps a tombstone
    /// frame to [Optional#empty] rather than a value, so the slot reads back as a known-empty hit and is not re-queried.
    public static final byte FMT_TOMBSTONE = 0x02;
    private static final Logger logger = LogManager.getLogger(CodecRegistry.class);
    /// How often a payload-decode failure may be logged.
    private static final Duration DECODE_FAILURE_LOG_INTERVAL = Duration.ofSeconds(30);
    /// Header of a tombstone frame: `[MAGIC, FMT_TOMBSTONE]`, no payload, no version.
    private static final int TOMBSTONE_HEADER_BYTES = 2;
    /// Header of a value frame: `[MAGIC, formatId, versionHi, versionLo]`, payload follows.
    private static final int VALUE_HEADER_BYTES = 4;
    private static final int DEFAULT_INITIAL_BUFFER_CAPACITY = 256;

    /// The complete framed bytes of a tombstone — a compile-time constant (`[MAGIC, FMT_TOMBSTONE]`, no payload), shared across all writes. The write path
    /// hands it straight to a read-only `ByteArrayInputStream`, so the single shared instance is never mutated.
    private static final byte[] TOMBSTONE_FRAME = {MAGIC, FMT_TOMBSTONE};

    private final Codec writeCodec;
    private final ImmutableMap<Byte, Codec> readCodecsByFormatId;
    private final ByteArrayOutputStream reusableEncodeBuffer = new ByteArrayOutputStream(DEFAULT_INITIAL_BUFFER_CAPACITY);

    public CodecRegistry(Codec writeCodec, Iterable<? extends Codec> readCodecs) {
        this.writeCodec = checkNotNull(writeCodec, "writeCodec");
        var builder = ImmutableMap.<Byte, Codec>builder();
        for (Codec codec : checkNotNull(readCodecs, "readCodecs")) {
            builder.put(codec.formatId(), codec);
        }
        readCodecsByFormatId = builder.buildOrThrow();
        checkArgument(readCodecsByFormatId.containsKey(writeCodec.formatId()),
                      "writeCodec (formatId %s) must also be present in readCodecs", writeCodec.formatId());
    }

    /// Encodes a stored slot value as a framed `byte[]` ready for storage. A present `value` becomes a `[MAGIC, formatId, versionHi, versionLo, ...payload]`
    /// frame via the write codec — a fresh array per call, safe to retain across subsequent encode cycles. `schemaVersion` is the value type's
    /// [CacheSchemaVersion], stamped so a later read can detect a shape change. An empty `value` becomes the shared tombstone frame `[MAGIC, FMT_TOMBSTONE]`
    /// (a negative-cache marker, no payload, version-exempt), an immutable constant the caller must not mutate.
    ///
    public byte[] encode(Optional<?> value, int schemaVersion) {
        checkNotNull(value, "value");
        // Internal invariant: the only production caller passes a version already validated by CacheSchemaVersions.resolve, so an assert documents the
        // 16-bit-field precondition without a runtime check on the write hot path.
        assert schemaVersion >= CacheSchemaVersions.MIN_VERSION && schemaVersion <= CacheSchemaVersions.MAX_VERSION
                : "schemaVersion must be in [" + CacheSchemaVersions.MIN_VERSION + ", " + CacheSchemaVersions.MAX_VERSION + "], was " + schemaVersion;
        return value.map(v -> encodeValue(v, schemaVersion)).orElse(TOMBSTONE_FRAME);
    }

    /// Classifies a stored frame as a [DecodeOutcome] — a malformed row becomes a [DecodeOutcome.Discard] the read path can wipe and recompute, leaving the
    /// rest of the range read intact:
    ///
    /// - tombstone frame (`[MAGIC, FMT_TOMBSTONE]`) → [DecodeOutcome.Empty];
    /// - a stored version `≠ expectedVersion` → [DecodeOutcome.Discard] with `alarming = false` (the routine post-deploy schema-change case);
    /// - wrong magic / unknown format id / frame too short / a payload the codec fails to decode → [DecodeOutcome.Discard] with `alarming = true` (genuine
    ///   corruption);
    /// - otherwise → [DecodeOutcome.Present] of the decoded value.
    ///
    /// [DecodeOutcome.Discard] reasons are built from frame metadata only (magic / format / version / length / failure category), never payload bytes.
    public <T> DecodeOutcome<T> decode(byte[] frame, TypeToken<T> type, int expectedVersion) {
        checkNotNull(frame, "frame");
        checkNotNull(type, "type");
        if (isTombstone(frame)) {
            return DecodeOutcome.Empty.instance();
        }
        if (frame.length < VALUE_HEADER_BYTES) {
            return new DecodeOutcome.Discard<>(true, "frame too short: " + frame.length + " byte(s)");
        }
        byte magic = frame[0];
        if (magic != MAGIC) {
            return new DecodeOutcome.Discard<>(true, "missing magic byte: expected " + Byte.toUnsignedInt(MAGIC) + " but got " + Byte.toUnsignedInt(magic));
        }
        byte formatId = frame[1];
        Codec codec = readCodecsByFormatId.get(formatId);
        if (codec == null) {
            return new DecodeOutcome.Discard<>(true, "unknown format id: " + Byte.toUnsignedInt(formatId));
        }
        int storedVersion = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        if (storedVersion != expectedVersion) {
            return new DecodeOutcome.Discard<>(false, "schema version mismatch: stored=" + storedVersion + " expected=" + expectedVersion);
        }
        try {
            return new DecodeOutcome.Present<>(codec.decodePayload(frame, VALUE_HEADER_BYTES, frame.length - VALUE_HEADER_BYTES, type));
        } catch (RuntimeException e) {
            // Log the full exception (message + stack) at INFO, anti-spammed so a stream-wide decode failure produces one stack trace per window, not one per
            // row. Cached values are never confidential, so logging the codec's exception is safe. The Discard reason stays metadata-only (format + exception
            // class) because it feeds a PII-free admin alert.
            AntiSpamLogger.log(logger, DECODE_FAILURE_LOG_INTERVAL, Level.INFO,
                               "Discarding undecodable cached value (codec format {}); wiping and recomputing the slot", Byte.toUnsignedInt(formatId), e);
            return new DecodeOutcome.Discard<>(true, "format " + Byte.toUnsignedInt(formatId) + " payload failed to decode: " + e.getClass().getSimpleName());
        }
    }

    private byte[] encodeValue(Object value, int schemaVersion) {
        reusableEncodeBuffer.reset();
        reusableEncodeBuffer.write(MAGIC);
        reusableEncodeBuffer.write(writeCodec.formatId());
        reusableEncodeBuffer.write((schemaVersion >>> 8) & 0xFF);
        reusableEncodeBuffer.write(schemaVersion & 0xFF);
        writeCodec.encodePayload(value, reusableEncodeBuffer);
        return reusableEncodeBuffer.toByteArray();
    }

    private static boolean isTombstone(byte[] frame) {
        return frame.length == TOMBSTONE_HEADER_BYTES && frame[0] == MAGIC && frame[1] == FMT_TOMBSTONE;
    }
}
