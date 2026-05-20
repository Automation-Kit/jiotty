package net.yudichev.jiotty.analyticscache;

import com.fasterxml.jackson.core.JsonFactory;

/// JSON-UTF-8 codec — `formatId = 0x00`. Diagnostic / inspection format; not the production write default (Smile is). Useful for:
///
/// - unit tests of the codec abstraction;
/// - per-stream overrides where human-readable storage in `psql` is preferred;
/// - any future tooling that needs to read rows without the Smile dependency.
final class JsonUtf8Codec extends AbstractJacksonCodec {
    public JsonUtf8Codec() {
        super(new JsonFactory());
    }

    @Override
    public byte formatId() {
        return CodecRegistry.FMT_JSON_UTF8;
    }
}
