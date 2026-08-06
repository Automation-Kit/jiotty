package net.yudichev.jiotty.user.ui;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.common.rest.HttpStatuses.METHOD_NOT_ALLOWED_405;

/// Handles `GET /ui/api/displayables` (exact). Returns the list of currently-visible registered displayables.
public final class GetDisplayablesListHandler implements ApiPathHandler {
    static final String PATH = "/displayables";
    private static final Pattern TAB_NAME_TO_ID_CONVERSION_PATTERN = Pattern.compile("[^A-Za-z0-9_-]");

    private final DisplayableRegistry registry;

    @Inject
    public GetDisplayablesListHandler(DisplayableRegistry registry) {
        this.registry = checkNotNull(registry, "registry");
    }

    @Override
    public String pathPrefix() {
        return PATH;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response) {
        if (!PATH.equals(request.getPathInfo())) {
            ApiServlet.writeUnknownPath(response);
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            response.setStatus(METHOD_NOT_ALLOWED_405);
            return;
        }
        asUnchecked(() -> writeList(response));
    }

    private void writeList(HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("utf-8");
        response.setContentType("application/json");
        Collection<Displayable> displayables = registry.all();
        var items = new ArrayList<Map<String, Object>>(displayables.size());
        for (Displayable displayable : displayables) {
            if (displayable.visible()) {
                items.add(Map.of("id", displayable.getId(),
                                 "name", displayable.getDisplayName(),
                                 "safeId", TAB_NAME_TO_ID_CONVERSION_PATTERN.matcher(displayable.getId()).replaceAll("-")));
            }
        }
        UIJson.WRITER.writeValue(response.getWriter(), Map.of("items", items));
    }
}
