package net.yudichev.jiotty.common.rest;

/// HTTP status codes, named `REASON_PHRASE_CODE` so the number stays visible at the call site. Covers the codes in use; add a constant when a new code is
/// needed.
public final class HttpStatuses {
    public static final int OK_200 = 200;
    public static final int CREATED_201 = 201;
    public static final int NO_CONTENT_204 = 204;
    public static final int BAD_REQUEST_400 = 400;
    public static final int UNAUTHORIZED_401 = 401;
    public static final int FORBIDDEN_403 = 403;
    public static final int NOT_FOUND_404 = 404;
    public static final int METHOD_NOT_ALLOWED_405 = 405;
    public static final int CONFLICT_409 = 409;
    public static final int GONE_410 = 410;
    public static final int PAYLOAD_TOO_LARGE_413 = 413;
    public static final int UNPROCESSABLE_ENTITY_422 = 422;
    public static final int TOO_MANY_REQUESTS_429 = 429;
    public static final int INTERNAL_SERVER_ERROR_500 = 500;
    public static final int BAD_GATEWAY_502 = 502;
    public static final int SERVICE_UNAVAILABLE_503 = 503;

    private HttpStatuses() {
    }

    /// @return `true` when `code` is a 2xx success.
    public static boolean isSuccess(int code) {
        return code / 100 == 2;
    }

    /// @return `true` when `code` is a 5xx server error.
    public static boolean isServerError(int code) {
        return code / 100 == 5;
    }
}
