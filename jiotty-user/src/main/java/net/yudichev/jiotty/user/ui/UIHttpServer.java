package net.yudichev.jiotty.user.ui;

public interface UIHttpServer {
    /// The root path under which every UI HTTP endpoint is mounted — `/ui/api/*` for the per-user API surface, `/ui/*` for static SPA resources. Used by every
    /// [ServletMount] in this package and by external callers (e.g. email/HTML templates) that need to construct UI links.
    String PATH_ROOT = "/ui";

    /// Request-attribute key a dispatcher sets to override the default `path` tag the server's request-timing hook derives from the URL. The value, if
    /// present, must be a [String] from a small fixed set — a matched handler's full URL path, or a [ServletMount]'s context path — never anything
    /// request-specific, since it becomes a metric tag.
    String ROUTE_NAME_ATTRIBUTE = "metrics.routeName";

    int listenPort();
}
