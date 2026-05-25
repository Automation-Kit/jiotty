package net.yudichev.jiotty.timeseriescache;

import com.google.common.reflect.TypeToken;

import java.io.OutputStream;

/// One concrete encoding for cached values. Codecs only handle *payload* bytes — the per-row format header (magic + format id) is owned by [CodecRegistry] so
/// the framing rule is implemented exactly once across all formats.
///
/// - [#encodePayload] appends payload bytes to a registry-supplied [OutputStream]. The registry owns the storage; bytes written here become part of the
/// registry's framed row directly.
/// - [#decodePayload] reads `length` bytes starting at `offset` from `frame` and returns the deserialised value. The codec consumes its payload from a slice of
/// the registry's framed row, leaving the framed array untouched.
///
/// @implSpec [#formatId] MUST be globally unique within the registry; the registry rejects duplicates at construction. Encode/decode must be inverse
/// operations: a value round-tripped through `encodePayload` + `decodePayload` (with a matching [TypeToken]) MUST equal the original under the type's normal
/// equality (record components, `equals`, etc.).
interface Codec {
    byte formatId();

    void encodePayload(Object value, OutputStream sink);

    <T> T decodePayload(byte[] frame, int offset, int length, TypeToken<T> type);
}
