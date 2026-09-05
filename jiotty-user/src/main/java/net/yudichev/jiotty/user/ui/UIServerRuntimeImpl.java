package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableSet;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

/// Pure request dispatcher: routes each request to the registered [ApiPathHandler] whose [ApiPathHandler#pathPrefix] is the longest match for the request path.
/// Owns no UI state — option/displayable registration lives behind [UIServer], not here.
public final class UIServerRuntimeImpl extends BaseLifecycleComponent implements UIServerRuntime {
    private final Set<ApiPathHandler> apiPathHandlers;
    private Map<String, ApiPathHandler> handlersByPrefix;

    @Inject
    public UIServerRuntimeImpl(Set<ApiPathHandler> apiPathHandlers) {
        this.apiPathHandlers = ImmutableSet.copyOf(apiPathHandlers);
    }

    @Override
    protected void doStart() {
        var byPrefix = LinkedHashMap.<String, ApiPathHandler>newLinkedHashMap(apiPathHandlers.size());
        for (ApiPathHandler handler : apiPathHandlers) {
            String prefix = handler.pathPrefix();
            checkArgument(prefix.startsWith("/"), "pathPrefix must start with '/': '%s' (handler %s)", prefix, handler);
            checkArgument(byPrefix.putIfAbsent(prefix, handler) == null,
                          "Two ApiPathHandlers claim the same prefix '%s': %s and %s", prefix, byPrefix.get(prefix), handler);
        }
        handlersByPrefix = byPrefix;
    }

    @Override
    public DispatchResult dispatchApiPath(HttpServletRequest request, HttpServletResponse response) {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            return DispatchResult.NOT_FOUND;
        }
        // No lock on this hot path: a request reaching a stopped/lifecycling runtime must report UNAVAILABLE rather than throw. isStarted() is a volatile
        // read, and handlersByPrefix is published through that same flag (written in doStart() before started flips true, never mutated afterwards or nulled
        // on stop), so reading it unlocked once started is safe. The caller decides how to render UNAVAILABLE.
        if (!isStarted()) {
            return DispatchResult.UNAVAILABLE;
        }
        ApiPathHandler handler = findMatchingHandler(pathInfo);
        if (handler == null) {
            return DispatchResult.NOT_FOUND;
        }
        // Set before invoking handle: SSE handlers commit the response inside flushBuffer, which fires Jetty's onResponseBegin while we are still inside
        // handle(); the metrics layer reads this attribute there to tag the request's TTFB with the matched route. The full URL path
        // (context-path + servlet-path + handler prefix) is stored rather than the bare pathPrefix so the tag is unambiguous across mounts.
        request.setAttribute(UIHttpServer.ROUTE_NAME_ATTRIBUTE,
                             request.getContextPath() + request.getServletPath() + handler.pathPrefix());
        handler.handle(request, response);
        return DispatchResult.HANDLED;
    }

    // TODO O(n) scan on the dispatch hot path with per-iteration `prefix + "/"` concatenation. Reachable optimisation: build a `Map<String, ApiPathHandler>`
    //  keyed by exact path-prefix at start time, then dispatch by truncating `pathInfo` at successive trailing `/` and looking up each truncation — O(d)
    //  where d is the segment count of the request path, independent of handler count. Relies on the invariant that registered prefixes are segment-aligned
    //  (start with `/`, do not end with `/`), which is already enforced by the `pathPrefix` contract. Skipped until either a profile flags this or handler
    //  counts climb out of single digits.
    private @Nullable ApiPathHandler findMatchingHandler(String pathInfo) {
        ApiPathHandler best = null;
        int bestLength = -1;
        for (Map.Entry<String, ApiPathHandler> entry : handlersByPrefix.entrySet()) {
            String prefix = entry.getKey();
            if ((pathInfo.equals(prefix) || pathInfo.startsWith(prefix + "/")) && prefix.length() > bestLength) {
                best = entry.getValue();
                bestLength = prefix.length();
            }
        }
        return best;
    }
}
