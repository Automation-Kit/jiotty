package net.yudichev.jiotty.common.lang;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.reflect.TypeToken;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;

import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

public final class Json {
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new GuavaModule());

    private Json() {
    }

    public static JsonNode parse(String json) {
        return getAsUnchecked(() -> mapper.readTree(json));
    }

    public static <T> T parse(String json, Class<T> type) {
        return getAsUnchecked(() -> mapper.readValue(json, type));
    }

    public static <T> T parse(String json, TypeToken<T> type) {
        return getAsUnchecked(() -> mapper.readValue(json, mapper.getTypeFactory().constructType(type.getType())));
    }

    /// Parses JSON directly from `bytes`, letting Jackson decode UTF-8 in a single streaming pass rather than first allocating an intermediate [String] (a full
    /// char[] copy). Prefer this over [#parse(String, Class)] when the payload is already a byte array — e.g. a size-capped request body read via `readNBytes`.
    public static <T> T parse(byte[] bytes, Class<T> type) {
        return getAsUnchecked(() -> mapper.readValue(bytes, type));
    }

    /// Parses JSON read directly from `reader`, avoiding the intermediate [String]/byte-array copy that reading the whole payload into memory first would
    /// incur. Jackson reads `reader` to completion and closes it. A caller holding a byte stream wraps it in an [InputStreamReader] with the desired charset.
    public static <T> T parse(Reader reader, Class<T> type) {
        return getAsUnchecked(() -> mapper.readValue(reader, type));
    }

    /// Generic-type-aware counterpart to [#parse(Reader, Class)]: pass a [TypeToken] to capture parameterised element types.
    public static <T> T parse(Reader reader, TypeToken<T> type) {
        return getAsUnchecked(() -> mapper.readValue(reader, mapper.getTypeFactory().constructType(type.getType())));
    }

    public static ObjectNode object() {
        return mapper.createObjectNode();
    }

    public static String stringify(Object value) {
        return getAsUnchecked(() -> mapper.writeValueAsString(value));
    }

    /// Streams the JSON-encoded form of `value` directly into `output`. Jackson uses an internal small buffer; no full-payload [String] or byte array is
    /// allocated on the caller's side. Useful for piping straight into an [java.net.http.HttpResponse] body, a `ServletOutputStream`, a `GZIPOutputStream`,
    /// or any other sink whose lifetime the caller manages.
    public static void writeTo(OutputStream output, Object value) {
        asUnchecked(() -> mapper.writeValue(output, value));
    }

    /// Returns an [ObjectWriter] pre-configured for the given Java type. The writer caches Jackson's serialiser graph for `type` once; subsequent
    /// `writer.writeValue(...)` calls skip the per-value reflective type lookup that [#stringify] / [#writeTo] perform every time they're invoked.
    ///
    /// Intended for hot-path JSON emission where the value's static type is known up-front and the same writer is reused across many writes — e.g. a typed
    /// HTTP response payload, a recurring SSE frame shape, a per-row recordable. Keep the returned writer in a `static final` (or constructor-frozen) field;
    /// don't call `createWriterFor` on every write or you've reintroduced the very lookup this avoids.
    ///
    /// @param type the value's compile-time type, including any generic parameters captured via [TypeToken] (e.g. `new TypeToken<Map<String, List<Row>>>() {}`)
    /// @return a thread-safe writer for `type`
    public static ObjectWriter createWriterFor(TypeToken<?> type) {
        return getAsUnchecked(() -> mapper.writerFor(mapper.constructType(type.getType())));
    }
}
