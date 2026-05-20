package net.yudichev.jiotty.user.ui;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.servlet.DispatcherType;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Handler;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.EnumSet;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.user.ui.UIHttpServer.PATH_ROOT;

/// Built-in [ServletMount] mounting the per-user API surface at `/ui/api/*`. Installs [RequestContextFilter] on every request inside the context and hosts the
/// single [ApiServlet] that dispatches built-in endpoints and user-registered [ApiPathHandler]s.
final class ApiServletMount implements ServletMount {
    static final String CONTEXT_PATH = PATH_ROOT + "/api";

    private final UIRequestAuthoriser requestAuthoriser;

    @Inject
    ApiServletMount(@Dependency UIRequestAuthoriser requestAuthoriser) {
        this.requestAuthoriser = checkNotNull(requestAuthoriser, "requestAuthoriser");
    }

    @Override
    public Handler buildHandler() {
        var contextHandler = new ServletContextHandler();
        contextHandler.setContextPath(CONTEXT_PATH);

        var filterHolder = new FilterHolder(new RequestContextFilter(requestAuthoriser));
        filterHolder.setAsyncSupported(true);
        contextHandler.addFilter(filterHolder, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        var servletHolder = new ServletHolder(new ApiServlet());
        servletHolder.setAsyncSupported(true);
        contextHandler.addServlet(servletHolder, "/*");

        return contextHandler;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
