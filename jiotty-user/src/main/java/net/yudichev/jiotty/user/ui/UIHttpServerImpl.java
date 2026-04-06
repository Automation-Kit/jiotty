package net.yudichev.jiotty.user.ui;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.ee10.servlet.DefaultServlet;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Objects.requireNonNull;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;
import static net.yudichev.jiotty.user.ui.UIRequestAuthoriser.UIRequestContext;

final class UIHttpServerImpl extends BaseLifecycleComponent implements UIHttpServer {
    private static final Logger logger = LogManager.getLogger(UIHttpServerImpl.class);
    private static final String REQUEST_CONTEXT = UIHttpServerImpl.class.getName() + ".requestContext";

    private final UIRequestAuthoriser requestAuthoriser;
    private final Server server;
    private final ServerConnector connector;

    @Inject
    UIHttpServerImpl(@Dependency UIRequestAuthoriser requestAuthoriser,
                     @ListenPort int listenPort) {
        this.requestAuthoriser = checkNotNull(requestAuthoriser, "requestAuthoriser");
        checkArgument(listenPort >= 0 && listenPort <= 65_535, "listenPort: %s", listenPort);
        server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setFormEncodedMethods("POST");

        connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        connector.setPort(listenPort);
        server.addConnector(connector);

        var servletContextHandler = new ServletContextHandler();
        servletContextHandler.setContextPath(AuthenticatedHttpServerModule.PATH_ROOT);
        String styleCssPath = requireNonNull(getClass().getResource("/uiserver/wwwroot/style.css")).toString();
        servletContextHandler.setBaseResourceAsString(styleCssPath.substring(0, styleCssPath.lastIndexOf('/')));

        var requestContextFilter = new FilterHolder(new RequestContextFilter());
        requestContextFilter.setAsyncSupported(true);
        servletContextHandler.addFilter(requestContextFilter, "/*", EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC));

        servletContextHandler.addServlet(createAsyncServletHolder(new OptionsServlet()), AuthenticatedHttpServerModule.SUB_PATH_OPTIONS);
        servletContextHandler.addServlet(createAsyncServletHolder(new DownloadServlet()), "/displayables/*");
        servletContextHandler.addServlet(createAsyncServletHolder(new ApiServlet()), "/api/*");

        var resourceServletHolder = new ServletHolder("default", DefaultServlet.class);
        resourceServletHolder.setInitParameter("dirAllowed", "false");
        servletContextHandler.addServlet(resourceServletHolder, "/");

        server.setHandler(new Handler.Sequence(servletContextHandler, new DefaultHandler()));
    }

    @Override
    public int listenPort() {
        return whenStartedAndNotLifecycling(connector::getLocalPort);
    }

    @Override
    protected void doStart() {
        asUnchecked(server::start);
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, server::stop);
    }

    private static ServletHolder createAsyncServletHolder(HttpServlet servlet) {
        var servletHolder = new ServletHolder(servlet);
        servletHolder.setAsyncSupported(true);
        return servletHolder;
    }

    static void setRequestContext(HttpServletRequest request, UIRequestContext requestContext) {
        request.setAttribute(REQUEST_CONTEXT, requestContext);
    }

    static UIRequestContext requestContext(HttpServletRequest request) {
        Object requestContext = request.getAttribute(REQUEST_CONTEXT);
        checkState(requestContext instanceof UIRequestContext, "Request context is not initialised");
        return (UIRequestContext) requestContext;
    }

    private static UIServerRuntime runtime(HttpServletRequest request) {
        return requestContext(request).uiServerRuntime();
    }

    private static String relativePath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    private static boolean requiresRequestContext(HttpServletRequest request) {
        String path = relativePath(request);
        return path.startsWith("/api/")
               || path.startsWith("/displayables/")
               || "POST".equals(request.getMethod());
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ListenPort {
    }

    private final class RequestContextFilter implements Filter {
        @Override
        public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
                throws IOException, ServletException {
            checkArgument(servletRequest instanceof HttpServletRequest, "Expected HttpServletRequest");
            checkArgument(servletResponse instanceof HttpServletResponse, "Expected HttpServletResponse");
            var request = (HttpServletRequest) servletRequest;
            var response = (HttpServletResponse) servletResponse;
            if (request.getAttribute(REQUEST_CONTEXT) != null || !requiresRequestContext(request)) {
                chain.doFilter(request, response);
                return;
            }
            requestAuthoriser.authorise(request, response, chain);
        }
    }

    private static final class OptionsServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            // TODO:commerce remove this redirect when the old UIServer browser SPA is deleted.
            response.sendRedirect(AuthenticatedHttpServerModule.PATH_ROOT + "/index.html");
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) {
            runtime(request).handleOptionsPost(request, response);
        }
    }

    private static final class DownloadServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) {
            runtime(request).handleDownload(request, response);
        }
    }

    private static final class ApiServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            UIServerRuntime runtime = runtime(request);
            switch (request.getPathInfo()) {
                case "/displayables" -> runtime.writeDisplayablesListJson(response);
                case "/displayables/item" -> runtime.writeDisplayableItemJson(request, response);
                case "/displayables/stream" -> startDisplayablesSse(request, response, runtime);
                case null, default -> {
                    response.setCharacterEncoding("utf-8");
                    response.setContentType("application/json");
                    response.setStatus(404);
                    response.getWriter().print("{\"error\":\"Unknown path\"}");
                }
            }
        }

        /// @implNote there are 3 threads acting on the state in this method; it looks hard to reason about, however, I was not able to fault it - looks solid
        private static void startDisplayablesSse(HttpServletRequest request, HttpServletResponse response, UIServerRuntime runtime) throws IOException {
            UIRequestContext requestContext = requestContext(request);
            var streamClosed = new AtomicBoolean();
            var invalidationSubscriptionRef = new AtomicReference<Closeable>();
            Closeable sseStream = runtime.startSse(request, response, () -> {
                streamClosed.set(true);
                closeSubscription(invalidationSubscriptionRef);
            });
            Closeable invalidationSubscription = requestContext.subscribeToInvalidation(sseStream::close);
            invalidationSubscriptionRef.set(invalidationSubscription);
            if (streamClosed.get()) {
                closeSubscription(invalidationSubscriptionRef);
            }
        }

        private static void closeSubscription(AtomicReference<Closeable> subscriptionRef) {
            Closeable.closeIfNotNull(subscriptionRef.getAndSet(null));
        }
    }
}
