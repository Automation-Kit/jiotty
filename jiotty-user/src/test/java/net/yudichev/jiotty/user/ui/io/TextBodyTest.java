package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static net.yudichev.jiotty.common.rest.ContentTypes.CHARSET_UTF_8;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_TEXT;
import static net.yudichev.jiotty.user.ui.io.testing.ResponseBodies.encode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TextBodyTest {
    @Test
    void aTextBodyIsLabelledPlainTextAndEncodesAsUtf8(@Mock HttpServletResponse response) {
        var body = new TextBody("café");

        body.applyHeadersTo(response);

        verify(response).setContentType(CONTENT_TYPE_TEXT);
        verify(response).setCharacterEncoding(CHARSET_UTF_8);
        assertThat(encode(body)).isEqualTo("café");
    }
}
