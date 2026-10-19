package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.rest.ContentTypes.CHARSET_UTF_8;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_TEXT;

/// A plain-text body, encoded as UTF-8.
public record TextBody(String text) implements ResponseBody {
    public TextBody {
        checkNotNull(text, "text");
    }

    @Override
    public void applyHeadersTo(HttpServletResponse response) {
        response.setCharacterEncoding(CHARSET_UTF_8);
        response.setContentType(CONTENT_TYPE_TEXT);
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
        output.write(text.getBytes(UTF_8));
    }
}
