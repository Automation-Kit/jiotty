package net.yudichev.jiotty.analyticscache;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.reflect.TypeToken;

import java.io.OutputStream;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

/// Common base for every Jackson-backed [Codec]. Subclasses supply the [JsonFactory] (which determines wire format) and the [#formatId]; the encode/decode
/// pipelines and module configuration (`Jdk8Module + JavaTimeModule + GuavaModule`) live here so all formats round-trip the same value space identically.
///
/// Encode streams payload bytes through the registry-supplied [OutputStream]. Decode reads directly from the registry's framed `byte[]` via Jackson's
/// `readValue(byte[], offset, len, ...)` overload — no [java.io.InputStream] wrapper needed.
abstract class AbstractJacksonCodec implements Codec {
    private final ObjectMapper mapper;

    protected AbstractJacksonCodec(JsonFactory factory) {
        mapper = new ObjectMapper(factory)
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .registerModule(new GuavaModule());
    }

    @Override
    public final void encodePayload(Object value, OutputStream sink) {
        asUnchecked(() -> mapper.writeValue(sink, value));
    }

    @Override
    public final <T> T decodePayload(byte[] frame, int offset, int length, TypeToken<T> type) {
        return getAsUnchecked(() -> mapper.readValue(frame, offset, length,
                                                     mapper.getTypeFactory().constructType(type.getType())));
    }
}
