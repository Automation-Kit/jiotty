package net.yudichev.jiotty.user.ui;

import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.user.ui.io.Answer;
import net.yudichev.jiotty.user.ui.io.JsonBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.rest.HttpStatuses.INTERNAL_SERVER_ERROR_500;

/// The `{"error": "<code>"}` envelope of a jiotty-user endpoint's failure response.
///
/// @param error a machine-readable code; must not carry wording meant for a user
public record ErrorResponse(String error) {
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    static final String MISSING_ID = "MISSING_ID";
    static final String UNKNOWN_ID = "UNKNOWN_ID";
    static final String INVALID_BODY = "INVALID_BODY";
    static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";

    private static final Logger logger = LogManager.getLogger(ErrorResponse.class);
    private static final ObjectWriter WRITER = UIJson.createWriterFor(ErrorResponse.class);
    /// The answer to a request that failed on this server's side.
    public static final Answer SERVER_FAULT = createAnswer(INTERNAL_SERVER_ERROR_500, INTERNAL_ERROR);

    public ErrorResponse {
        checkNotNull(error, "error");
    }

    /// Creates an answer with `status` and this envelope for `code`.
    public static Answer createAnswer(int status, String code) {
        return Answer.of(status, createBody(code));
    }

    /// Writes `status` and this envelope for `code` to a response still on its servlet thread. A write that fails has nowhere left to report to — this
    /// response *is* the report — so it is logged and dropped.
    static void write(HttpServletResponse response, int status, String code) {
        response.setStatus(status);
        JsonBody body = createBody(code);
        body.applyHeadersTo(response);
        try {
            body.writeTo(response.getOutputStream());
        } catch (IOException e) {
            logger.debug("Could not write the {} response", code, e);
        }
    }

    private static JsonBody createBody(String code) {
        return new JsonBody(WRITER, new ErrorResponse(code));
    }
}
