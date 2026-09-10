package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

import static net.yudichev.jiotty.common.rest.ContentTypes.CONTENT_TYPE_JSON;

/// The `{"error": "<code>"}` envelope every handler in this package answers a failure with. `error` is a machine-readable code, and the client owns the
/// sentence it shows for it, so no wording a user might read belongs in this field.
record ErrorResponse(String error) {
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String MISSING_ID = "MISSING_ID";
    static final String UNKNOWN_ID = "UNKNOWN_ID";
    static final String INVALID_BODY = "INVALID_BODY";
    static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";

    private static final Logger logger = LogManager.getLogger(ErrorResponse.class);
    private static final ObjectWriter WRITER = UIJson.createWriterFor(ErrorResponse.class);

    /// Answers with `status` and `code`. A write that fails has nowhere left to report to — this response *is* the report — so it is logged and dropped.
    static void write(HttpServletResponse response, int status, String code) {
        response.setStatus(status);
        response.setCharacterEncoding("utf-8");
        response.setContentType(CONTENT_TYPE_JSON);
        try {
            WRITER.writeValue(response.getOutputStream(), new ErrorResponse(code));
        } catch (IOException e) {
            logger.debug("Could not write the {} response", code, e);
        }
    }
}
