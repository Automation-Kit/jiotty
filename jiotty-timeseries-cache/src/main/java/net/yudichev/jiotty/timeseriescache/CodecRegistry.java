package net.yudichev.jiotty.timeseriescache;

import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;

import java.io.ByteArrayOutputStream;

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
/// through it without per-row buffer allocation. Each [#encode] call returns a fresh `byte[]` (via `toByteArray()`), so batched callers can retain the result
/// across encode cycles safely.
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

    private static final int HEADER_BYTES = 2;
    private static final int DEFAULT_INITIAL_BUFFER_CAPACITY = 256;

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

    /// Encodes `value` as a framed `byte[]` ready for storage. Returns a fresh array per call — safe to retain across subsequent encode cycles.
    public byte[] encode(Object value) {
        reusableEncodeBuffer.reset();
        reusableEncodeBuffer.write(MAGIC);
        reusableEncodeBuffer.write(writeCodec.formatId());
        writeCodec.encodePayload(value, reusableEncodeBuffer);
        return reusableEncodeBuffer.toByteArray();
    }

    /// Decodes a framed `byte[]`. Validates `MAGIC` at index 0, dispatches by the `formatId` at index 1, and hands the payload slice (indices 2..frame.length)
    /// to the matching read codec.
    public <T> T decode(byte[] frame, TypeToken<T> type) {
        checkNotNull(frame, "frame");
        checkNotNull(type, "type");
        checkArgument(frame.length >= HEADER_BYTES, "frame too short: %s", frame.length);
        byte magic = frame[0];
        checkArgument(magic == MAGIC, "missing magic byte: expected %s but got %s",
                      Byte.toUnsignedInt(MAGIC), Byte.toUnsignedInt(magic));
        byte formatId = frame[1];
        Codec codec = readCodecsByFormatId.get(formatId);
        checkArgument(codec != null, "unknown format id: %s (known: %s)",
                      Byte.toUnsignedInt(formatId), readCodecsByFormatId.keySet());
        return codec.decodePayload(frame, HEADER_BYTES, frame.length - HEADER_BYTES, type);
    }
}
