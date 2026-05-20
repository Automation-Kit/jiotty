package net.yudichev.jiotty.user.ui;

public interface UIHttpServer {
    /// The root path under which every UI HTTP endpoint is mounted — `/ui/api/*` for the per-user API surface, `/ui/*` for static SPA resources. Used by every
    /// [ServletMount] in this package and by external callers (e.g. email/HTML templates) that need to construct UI links.
    String PATH_ROOT = "/ui";

    int listenPort();
}
