package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface UIServerRuntime {
    /// Dispatches the request to a previously registered [ApiPathHandler] when the request path matches one of the registered prefixes.
    ///
    /// @return the outcome the caller renders: [DispatchResult#HANDLED] (nothing more to do), [DispatchResult#NOT_FOUND] (write 404), or
    /// [DispatchResult#UNAVAILABLE] (write a retryable 503 — the runtime is stopped or mid-lifecycle).
    DispatchResult dispatchApiPath(HttpServletRequest request, HttpServletResponse response);

    /// Outcome of [#dispatchApiPath].
    enum DispatchResult {
        /// A matching handler was found and invoked; the response is (being) written by the handler.
        HANDLED,
        /// No registered prefix matched the request path.
        NOT_FOUND,
        /// The runtime is not currently serving (stopped or mid-lifecycle); the request should be retried.
        UNAVAILABLE
    }
}
