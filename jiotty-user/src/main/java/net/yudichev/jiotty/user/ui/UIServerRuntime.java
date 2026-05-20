package net.yudichev.jiotty.user.ui;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface UIServerRuntime {
    /// Dispatches the request to a previously registered [ApiPathHandler] when the request path matches one of the registered prefixes.
    ///
    /// @return `true` iff a handler was found and invoked; `false` if no prefix matched (caller writes 404).
    boolean dispatchApiPath(HttpServletRequest request, HttpServletResponse response);
}
