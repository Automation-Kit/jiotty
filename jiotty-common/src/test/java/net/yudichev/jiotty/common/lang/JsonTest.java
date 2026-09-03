package net.yudichev.jiotty.common.lang;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonTest {
    private static InputStreamReader reader(String json, Charset charset) {
        return new InputStreamReader(new ByteArrayInputStream(json.getBytes(charset)), charset);
    }

    @Test
    void parsesFromReaderIntoClass() {
        assertThat(Json.parse(reader("{\"x\":1,\"y\":2}", UTF_8), Point.class)).isEqualTo(new Point(1, 2));
    }

    @Test
    void parsesFromByteArrayIntoClass() {
        assertThat(Json.parse("{\"x\":1,\"y\":2}".getBytes(UTF_8), Point.class)).isEqualTo(new Point(1, 2));
    }

    @Test
    void parsesFromACharSequenceBuiltPiecewise() {
        var json = new StringBuilder("{\"x\":1,").append("\"y\":2}");

        assertThat(Json.parse(json, Point.class)).isEqualTo(new Point(1, 2));
    }

    /// A [String] argument must keep resolving to the [String] overload, so adding the [CharSequence] one changed no existing call site's behaviour.
    @Test
    void aStringArgumentStillParsesIdentically() {
        assertThat(Json.parse("{\"x\":1,\"y\":2}", Point.class)).isEqualTo(new Point(1, 2));
    }

    @Test
    void parsesFromReaderIntoGenericType() {
        assertThat(Json.parse(reader("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]", UTF_8), new TypeToken<List<Point>>() {}))
                .containsExactly(new Point(1, 2), new Point(3, 4));
    }

    @Test
    void decodesTheStreamWithTheReadersCharset() {
        // 'café' encoded in ISO-8859-1 (é is the single byte 0xE9); reading with the matching charset proves the reader's charset is honoured
        assertThat(Json.parse(reader("{\"value\":\"café\"}", ISO_8859_1), Named.class).value()).isEqualTo("café");
    }

    @Test
    void writesIntoAnAppendable() {
        var out = new StringBuilder();

        Json.writeTo(out, new Point(1, 2));

        assertThat(out).hasToString("{\"x\":1,\"y\":2}");
    }

    /// The point of the [Appendable] overload is to extend a buffer the caller is already assembling, so it must append rather than replace, and must leave
    /// the buffer usable afterwards — Jackson closes the sink it is handed, and a closed wrapper must not take the caller's builder down with it.
    @Test
    void appendsIntoABufferTheCallerKeepsWriting() {
        var out = new StringBuilder("prefix ");

        Json.writeTo(out, new Point(1, 2));
        out.append(" suffix");

        assertThat(out).hasToString("prefix {\"x\":1,\"y\":2} suffix");
    }

    /// The pre-built writer must produce byte-for-byte what the untyped overload does, since the only reason to reach for it is speed.
    @Test
    void writesThroughAPreBuiltWriterExactlyAsTheUntypedOverloadDoes() {
        var throughWriterOutput = new StringBuilder();
        var untypedOutput = new StringBuilder();

        Json.writeTo(throughWriterOutput, Json.createWriterFor(new TypeToken<Point>() {}), new Point(1, 2));
        Json.writeTo(untypedOutput, new Point(1, 2));

        assertThat(throughWriterOutput).hasToString(untypedOutput.toString());
    }

    /// The [Class] overload exists so the common non-generic case needs no [TypeToken], so it must produce the same writer.
    @Test
    void buildsTheSameWriterFromAClassAsFromATypeToken() {
        var fromClassOutput = new StringBuilder();
        var fromTypeTokenOutput = new StringBuilder();

        Json.writeTo(fromClassOutput, Json.createWriterFor(Point.class), new Point(1, 2));
        Json.writeTo(fromTypeTokenOutput, Json.createWriterFor(new TypeToken<Point>() {}), new Point(1, 2));

        assertThat(fromClassOutput).hasToString(fromTypeTokenOutput.toString());
    }

    /// Same buffer contract as the untyped overload: append, and leave the caller's builder usable after Jackson has closed the sink it was handed.
    @Test
    void appendsThroughAPreBuiltWriterIntoABufferTheCallerKeepsWriting() {
        var out = new StringBuilder("prefix ");

        Json.writeTo(out, Json.createWriterFor(new TypeToken<Point>() {}), new Point(1, 2));
        out.append(" suffix");

        assertThat(out).hasToString("prefix {\"x\":1,\"y\":2} suffix");
    }

    /// Binding a sub-tree must behave exactly as parsing that sub-tree's own text would, since the whole reason to keep an island of a payload as a tree is
    /// that its type is only known further in.
    @Test
    void bindsAlreadyParsedTreeToAType() {
        JsonNode tree = Json.parse("{\"outer\": {\"x\": 1, \"y\": 2}}");

        assertThat(Json.convert(tree.get("outer"), Point.class)).isEqualTo(new Point(1, 2));
    }

    /// A tree that does not fit the type must throw rather than yield a half-bound value: callers binding an island of someone else's payload rely on the
    /// throw to tell them the island was not what they expected.
    @Test
    void throwsWhenTheTreeDoesNotFitTheType() {
        JsonNode tree = Json.parse("{\"outer\": {\"x\": {\"nested\": true}}}");

        assertThatThrownBy(() -> Json.convert(tree.get("outer"), Point.class)).isInstanceOf(RuntimeException.class);
    }

    private record Point(int x, int y) {}

    private record Named(String value) {}
}
