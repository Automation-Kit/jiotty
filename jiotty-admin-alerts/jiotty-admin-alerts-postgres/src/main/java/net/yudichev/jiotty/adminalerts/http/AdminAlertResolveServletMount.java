package net.yudichev.jiotty.adminalerts.http;

import jakarta.inject.Inject;
import jakarta.servlet.DispatcherType;
import net.yudichev.jiotty.user.ui.ServletMount;
import net.yudichev.jiotty.user.ui.UIHttpServer;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Handler;

import java.util.EnumSet;

import static com.google.common.base.Preconditions.checkNotNull;

/// Builds the Jetty handler for the `/admin/api/*` surface. Registered as a [ServletMount] in the host [UIHttpServer].
public final class AdminAlertResolveServletMount implements ServletMount {
    static final String CONTEXT_PATH = "/admin";
    static final String FILTER_PATH_SPEC = "/api/*";
    static final String SERVLET_PATH_SPEC = "/api/alerts/*";

    private final AdminBearerAuthFilter authFilter;
    private final AdminAlertResolveServlet resolveServlet;

    @Inject
    public AdminAlertResolveServletMount(AdminBearerAuthFilter authFilter, AdminAlertResolveServlet resolveServlet) {
        this.authFilter = checkNotNull(authFilter, "authFilter");
        this.resolveServlet = checkNotNull(resolveServlet, "resolveServlet");
    }

    @Override
    public Handler buildHandler() {
        var contextHandler = new ServletContextHandler();
        contextHandler.setContextPath(CONTEXT_PATH);
        var filterHolder = new FilterHolder(authFilter);
        filterHolder.setAsyncSupported(true);
        contextHandler.addFilter(filterHolder, FILTER_PATH_SPEC, EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));
        var servletHolder = new ServletHolder(resolveServlet);
        servletHolder.setAsyncSupported(true);
        contextHandler.addServlet(servletHolder, SERVLET_PATH_SPEC);
        return contextHandler;
    }
}
