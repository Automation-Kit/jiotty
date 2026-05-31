package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Per-cache-instance registry that owns the wire-framing rule and dispatches reads to the right [Codec] by per-row format id.
///
/// Wire layout of every framed value: `[MAGIC, formatId, ...payload]`. The single-byte `MAGIC` is a parity check that detects "this column was not written by
/// our codec" (corruption, hand-inserted rows, unrelated bytes); reads with the wrong magic fail loudly rather than mis-decode. The 1-byte `formatId` selects
/// the codec.
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

    private static final int HEADER_BYTES = 2;
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

    /// Encodes a stored slot value as a framed `byte[]` ready for storage. A present `value` becomes a `[MAGIC, formatId, ...payload]` frame via the write
    /// codec — a fresh array per call, safe to retain across subsequent encode cycles. An empty `value` becomes the shared tombstone frame
    /// `[MAGIC, FMT_TOMBSTONE]` (a negative-cache marker, no payload), an immutable constant the caller must not mutate.
    public byte[] encode(Optional<?> value) {
        checkNotNull(value, "value");
        return value.map(this::encodeValue).orElse(TOMBSTONE_FRAME);
    }

    /// Decodes a stored frame. A tombstone frame (`[MAGIC, FMT_TOMBSTONE]`) decodes to [Optional#empty] — a negative-cache marker carrying no value. Any other
    /// frame is validated (`MAGIC` at index 0, a known `formatId` at index 1) and its payload slice (indices 2..frame.length) handed to the matching read
    /// codec, yielding [Optional#of] the value.
    ///
    /// @throws IllegalArgumentException if `frame` is not a tombstone and is too short, carries the wrong magic byte, or names an unknown format id
    public <T> Optional<T> decode(byte[] frame, TypeToken<T> type) {
        checkNotNull(frame, "frame");
        checkNotNull(type, "type");
        if (isTombstone(frame)) {
            return Optional.empty();
        }
        checkArgument(frame.length >= HEADER_BYTES, "frame too short: %s", frame.length);
        byte magic = frame[0];
        checkArgument(magic == MAGIC, "missing magic byte: expected %s but got %s",
                      Byte.toUnsignedInt(MAGIC), Byte.toUnsignedInt(magic));
        byte formatId = frame[1];
        Codec codec = readCodecsByFormatId.get(formatId);
        checkArgument(codec != null, "unknown format id: %s (known: %s)",
                      Byte.toUnsignedInt(formatId), readCodecsByFormatId.keySet());
        return Optional.of(codec.decodePayload(frame, HEADER_BYTES, frame.length - HEADER_BYTES, type));
    }

    private byte[] encodeValue(Object value) {
        reusableEncodeBuffer.reset();
        reusableEncodeBuffer.write(MAGIC);
        reusableEncodeBuffer.write(writeCodec.formatId());
        writeCodec.encodePayload(value, reusableEncodeBuffer);
        return reusableEncodeBuffer.toByteArray();
    }

    private static boolean isTombstone(byte[] frame) {
        return frame.length == HEADER_BYTES && frame[0] == MAGIC && frame[1] == FMT_TOMBSTONE;
    }
}
