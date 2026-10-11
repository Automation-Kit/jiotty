package net.yudichev.jiotty.common.rest;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.findInCausalChain;

/// Thrown by [RestClients]' calls when the server returns a non-2xx status, carrying its [#statusCode] and [#body]; [#findIn] locates it in a failure's
/// causal chain.
///
/// The message reads `Response code N, body: ...`; string-matching callers depend on that format.
public final class HttpResponseException extends RuntimeException {
    private final int statusCode;
    private final String body;

    public HttpResponseException(int statusCode, String body) {
        super("Response code " + statusCode + ", body: " + checkNotNull(body));
        checkArgument(statusCode > 0, "statusCode must be positive, was %s", statusCode);
        this.statusCode = statusCode;
        this.body = body;
    }

    /// @return the [HttpResponseException] in `throwable`'s causal chain, or empty when the failure carries no HTTP response; an [HttpResponseException] is
    ///         always a leaf, so the chain holds at most one
    public static Optional<HttpResponseException> findIn(Throwable throwable) {
        return findInCausalChain(throwable, HttpResponseException.class);
    }

    public int statusCode() {
        return statusCode;
    }

    public String body() {
        return body;
    }
}
