package net.yudichev.jiotty.user.ui;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/// Pluggable per-user HTTP handler for one path prefix under `/ui/api/<prefix>/...`. Each handler advertises the prefix it owns; the runtime resolves an
/// incoming request to a handler by longest-prefix-wins on the request's path-info. How handlers are registered with the runtime is an implementation detail of
/// the host application — typically they are contributed at construction time.
///
/// @implSpec [#handle] is invoked synchronously on the servlet container's request thread. Implementations that perform any non-trivial work — I/O, database
/// access, dispatch onto their own executor — MUST use [AsyncContext] (via [HttpServletRequest#startAsync]) and complete the response only from inside the
/// async callback, so the servlet thread is released promptly.
public interface ApiPathHandler {
    /// Returns the path-info prefix this handler owns under `/ui/api`. Must start with `/`. The handler is routed any request whose path-info equals the prefix
    /// or starts with `prefix + "/"`; longest matching prefix wins.
    String pathPrefix();

    /// Handles the request.
    void handle(HttpServletRequest request, HttpServletResponse response);
}
