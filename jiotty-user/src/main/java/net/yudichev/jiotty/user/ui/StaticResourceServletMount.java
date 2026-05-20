package net.yudichev.jiotty.user.ui;

import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Handler;

import static java.util.Objects.requireNonNull;
import static net.yudichev.jiotty.user.ui.UIHttpServer.PATH_ROOT;

/// Built-in [ServletMount] mounting the SPA static resources at `/ui/*`. Pure static — no filter, no per-user state, no auth. All authenticated endpoints have
/// moved under `/ui/api/*` (handled by [ApiServletMount] + [ApiPathHandler]s).
final class StaticResourceServletMount implements ServletMount {

    @Override
    public Handler buildHandler() {
        var contextHandler = new ServletContextHandler();
        contextHandler.setContextPath(PATH_ROOT);
        String styleCssPath = requireNonNull(getClass().getResource("/uiserver/wwwroot/style.css")).toString();
        contextHandler.setBaseResourceAsString(styleCssPath.substring(0, styleCssPath.lastIndexOf('/')));

        var resourceServletHolder = new ServletHolder("default", DefaultServlet.class);
        resourceServletHolder.setInitParameter("dirAllowed", "false");
        contextHandler.addServlet(resourceServletHolder, "/");

        return contextHandler;
    }
}
