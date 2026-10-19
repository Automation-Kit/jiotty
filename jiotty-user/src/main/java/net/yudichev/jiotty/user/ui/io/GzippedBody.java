package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CONTENT_ENCODING;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_CODING_GZIP;

/// A [ResponseBody] that gzip-compresses another body and sets `Content-Encoding: gzip`.
///
/// @param body the body to compress
public record GzippedBody(ResponseBody body) implements ResponseBody {
    public GzippedBody {
        checkNotNull(body, "body");
    }

    @Override
    public void applyHeadersTo(HttpServletResponse response) {
        body.applyHeadersTo(response);
        response.setHeader(CONTENT_ENCODING, CONTENT_CODING_GZIP);
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
        // UncloseableFilterOutputStream keeps `output` open once the gzip stream has written its trailer.
        try (var gzip = new GZIPOutputStream(new UncloseableFilterOutputStream(output))) {
            body.writeTo(gzip);
        }
    }
}
