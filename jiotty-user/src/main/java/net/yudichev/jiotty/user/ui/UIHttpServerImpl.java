package net.yudichev.jiotty.user.ui;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

/// Thin Jetty host. Composes all injected [ServletMount]s into a single [ContextHandlerCollection] (which routes by longest matching context path) and installs
/// [JsonErrorHandler] as the server-wide error handler so requests not matching any mount return a JSON `{"error":"Not found"}` envelope via
/// [Response#writeError].
final class UIHttpServerImpl extends BaseLifecycleComponent implements UIHttpServer {
    private static final Logger logger = LogManager.getLogger(UIHttpServerImpl.class);

    private final Set<ServletMount> servletMounts;
    private final Server server;
    private final ServerConnector connector;

    @Inject
    UIHttpServerImpl(@ListenPort int listenPort, Set<ServletMount> servletMounts) {
        this.servletMounts = checkNotNull(servletMounts, "servletMounts");
        checkArgument(listenPort >= 0 && listenPort <= 65_535, "listenPort: %s", listenPort);
        server = new Server();
        var httpConfig = new HttpConfiguration();
        httpConfig.setFormEncodedMethods("POST");
        connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(listenPort);
        server.addConnector(connector);
    }

    @Override
    public int listenPort() {
        return whenStartedAndNotLifecycling(connector::getLocalPort);
    }

    @Override
    protected void doStart() {
        var contexts = new ContextHandlerCollection();
        for (ServletMount mount : servletMounts) {
            contexts.addHandler(mount.buildHandler());
        }
        server.setHandler(contexts);
        server.setErrorHandler(new JsonErrorHandler());
        asUnchecked(server::start);
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, server::stop);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ListenPort {
    }
}
