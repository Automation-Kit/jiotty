package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.lang.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static net.yudichev.jiotty.common.rest.ContentTypes.CHARSET_UTF_8;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;
import static net.yudichev.jiotty.user.ui.io.testing.ResponseBodies.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JsonBodyTest {
    @Test
    void aJsonBodyIsLabelledJsonAndEncodesItsValue(@Mock HttpServletResponse response) {
        var body = new JsonBody(Json.createWriterFor(Point.class), new Point(1, 2));

        body.applyHeadersTo(response);

        verify(response).setContentType(CONTENT_TYPE_JSON);
        verify(response).setCharacterEncoding(CHARSET_UTF_8);
        assertThat(encode(body)).isEqualTo("{\"x\":1,\"y\":2}");
    }

    private record Point(int x, int y) {}
}
