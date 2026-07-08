package net.yudichev.jiotty.common.lang;

import com.google.common.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.List;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class JsonTest {
    private static InputStreamReader reader(String json, Charset charset) {
        return new InputStreamReader(new ByteArrayInputStream(json.getBytes(charset)), charset);
    }

    @Test
    void parsesFromReaderIntoClass() {
        assertThat(Json.parse(reader("{\"x\":1,\"y\":2}", UTF_8), Point.class)).isEqualTo(new Point(1, 2));
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

    private record Point(int x, int y) {}

    private record Named(String value) {}
}
