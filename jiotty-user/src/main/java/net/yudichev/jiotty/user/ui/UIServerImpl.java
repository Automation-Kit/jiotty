package net.yudichev.jiotty.user.ui;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.user.ui.options.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/// Thin façade exposing the user-facing [UIServer] and the servlet-facing [UIServerRuntime] over the underlying [OptionRegistry], [DisplayableRegistry], and
/// the multibound set of [ApiPathHandler]s. Owns no business state — registration calls delegate to the relevant registry, and [#dispatchApiPath] looks up the
/// longest matching [ApiPathHandler#pathPrefix] in the injected handler set.
public final class UIServerImpl extends BaseLifecycleComponent implements UIServer, UIServerRuntime {
    private final OptionRegistry optionRegistry;
    private final DisplayableRegistry displayableRegistry;
    private final Set<ApiPathHandler> apiPathHandlers;

    private Map<String, ApiPathHandler> handlersByPrefix;

    @Inject
    public UIServerImpl(OptionRegistry optionRegistry,
                        DisplayableRegistry displayableRegistry,
                        Set<ApiPathHandler> apiPathHandlers) {
        this.optionRegistry = checkNotNull(optionRegistry, "optionRegistry");
        this.displayableRegistry = checkNotNull(displayableRegistry, "displayableRegistry");
        this.apiPathHandlers = checkNotNull(apiPathHandlers, "apiPathHandlers");
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
    public Closeable registerDisplayable(Displayable displayable) {
        return displayableRegistry.register(displayable);
    }

    @Override
    public Closeable registerOption(Option<?> option) {
        return optionRegistry.register(option);
    }

    @Override
    public boolean dispatchApiPath(HttpServletRequest request, HttpServletResponse response) {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            return false;
        }
        ApiPathHandler handler = whenStartedAndNotLifecycling(() -> findMatchingHandler(pathInfo));
        if (handler == null) {
            return false;
        }
        handler.handle(request, response);
        return true;
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
