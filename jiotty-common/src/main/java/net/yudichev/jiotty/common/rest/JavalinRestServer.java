package net.yudichev.jiotty.common.rest;

import com.google.inject.BindingAnnotation;
import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class JavalinRestServer extends BaseLifecycleComponent implements RestServer {
    private static final Logger logger = LogManager.getLogger(JavalinRestServer.class);

    private final int listenPort;
    private final List<Endpoint> pendingRoutes = new ArrayList<>();
    private volatile Javalin javalin;

    @Inject
    public JavalinRestServer(@ListenPort int listenPort) {
        checkArgument(listenPort >= 0 && listenPort <= 65_535, "listenPort: %s", listenPort);
        this.listenPort = listenPort;
    }

    @Override
    public void post(String url, Handler handler) {
        addRoute(HandlerType.POST, url, handler);
    }

    @Override
    public void get(String path, Handler handler) {
        addRoute(HandlerType.GET, path, handler);
    }

    @Override
    public int port() {
        checkState(isStarted(), "Server is not started");
        return javalin.port();
    }

    private void addRoute(HandlerType method, String path, Handler handler) {
        checkState(!isStarted(), "Cannot add routes after server has started");
        pendingRoutes.add(new Endpoint(method, path, handler));
    }

    @Override
    public void doStart() {
        javalin = Javalin.create(config -> pendingRoutes.forEach(config.routes::addEndpoint)).start(listenPort);
        pendingRoutes.clear();
        logger.info("REST service started on port {}: {}", javalin.port(), javalin);
    }

    @Override
    protected void doStop() {
        javalin.stop();
    }

    @Retention(RUNTIME)
    @Target({FIELD, PARAMETER, METHOD})
    @BindingAnnotation
    @interface ListenPort {
    }
}
