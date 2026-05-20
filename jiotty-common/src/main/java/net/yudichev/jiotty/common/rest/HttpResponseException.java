package net.yudichev.jiotty.common.rest;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Thrown by [RestClients#call] when the server returns a non-2xx status. Callers that need to react to specific HTTP codes (e.g. transition an auth-state
/// observable to [net.yudichev.jiotty.common.security.AuthState.PermanentFailure] on 401/403) can `instanceof`-match this type and read [#statusCode] / [#body]
/// directly, instead of regex-matching the message.
///
/// The exception message format is preserved verbatim from the previous untyped form (`"Response code N, body: ..."`) so string-matching callers keep working.
public final class HttpResponseException extends RuntimeException {
    private final int statusCode;
    private final String body;

    public HttpResponseException(int statusCode, String body) {
        super("Response code " + statusCode + ", body: " + checkNotNull(body));
        checkArgument(statusCode > 0, "statusCode must be positive, was %s", statusCode);
        this.statusCode = statusCode;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    public String body() {
        return body;
    }
}
