package net.yudichev.jiotty.user.ui.io;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;

/// A response body held as the data it carries, and encoded into bytes by [#writeTo] on the thread that writes the response.
///
/// @implSpec an implementation must hold immutable data only: the body is built on one thread and encoded on another, possibly more than once.
public interface ResponseBody {
    /// A body of no bytes, for an answer that is a status alone.
    ResponseBody EMPTY = new ResponseBody() {
        @Override
        public void applyHeadersTo(HttpServletResponse response) {
        }

        @Override
        public void writeTo(OutputStream output) {
        }
    };

    /// Sets the headers that describe this body, such as its content type.
    void applyHeadersTo(HttpServletResponse response);

    /// Encodes this body into `output`.
    ///
    /// @throws IOException if the body cannot be encoded, which is a defect in the data it carries, or `output` fails
    void writeTo(OutputStream output) throws IOException;
}
