package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.user.ui.io.testing.CloseTrackingOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;

import static com.google.common.net.HttpHeaders.CONTENT_ENCODING;
import static java.nio.charset.StandardCharsets.UTF_8;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_CODING_GZIP;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_TEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GzippedBodyTest {
    @Test
    void aGzippedBodyCarriesItsBodysHeadersAndEncodesTheCompressedBody(@Mock HttpServletResponse response) {
        var body = new GzippedBody(new TextBody("compress me"));

        body.applyHeadersTo(response);

        verify(response).setContentType(CONTENT_TYPE_TEXT);
        verify(response).setHeader(CONTENT_ENCODING, CONTENT_CODING_GZIP);
        var bytes = new ByteArrayOutputStream();
        asUnchecked(() -> body.writeTo(bytes));
        String decodedText = getAsUnchecked(() -> {
            try (var decompressor = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return new String(decompressor.readAllBytes(), UTF_8);
            }
        });
        assertThat(decodedText).isEqualTo("compress me");
    }

    /// The compressed body ends its own compressor, and the stream it writes into stays open for whatever the exchange writes after it.
    @Test
    void aGzippedBodyLeavesTheStreamItWritesIntoOpen() {
        var output = new CloseTrackingOutputStream();

        asUnchecked(() -> new GzippedBody(new TextBody("compress me")).writeTo(output));

        assertThat(output.isClosed()).isFalse();
        assertThat(output.size()).isPositive();
    }
}
