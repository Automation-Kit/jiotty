package net.yudichev.jiotty.user.ui.io;

import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.rest.ContentTypes.CHARSET_UTF_8;
import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;

/// A JSON response body.
///
/// @param writer the writer that encodes `value`; one for a type known at compile time should be built once and shared
/// @param value  the data, which must not change once the body is built
public record JsonBody(ObjectWriter writer, Object value) implements ResponseBody {
    public JsonBody {
        checkNotNull(writer, "writer");
        checkNotNull(value, "value");
    }

    @Override
    public void applyHeadersTo(HttpServletResponse response) {
        response.setCharacterEncoding(CHARSET_UTF_8);
        response.setContentType(CONTENT_TYPE_JSON);
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
        writer.writeValue(output, value);
    }
}
