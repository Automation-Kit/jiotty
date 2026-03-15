package net.yudichev.jiotty.common.rest;

import io.javalin.Javalin;
import io.javalin.http.Handler;
import io.javalin.http.HandlerType;
import io.javalin.router.Endpoint;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;

final class JavalinRestServer extends BaseLifecycleComponent implements RestServer {
    private static final Logger logger = LoggerFactory.getLogger(JavalinRestServer.class);

    private final List<Endpoint> pendingRoutes = new ArrayList<>();
    private volatile Javalin javalin;

    @Override
    public void post(String url, Handler handler) {
        addRoute(HandlerType.POST, url, handler);
    }

    @Override
    public void get(String path, Handler handler) {
        addRoute(HandlerType.GET, path, handler);
    }

    private void addRoute(HandlerType method, String path, Handler handler) {
        checkState(!isStarted(), "Cannot add routes after server has started");
        pendingRoutes.add(new Endpoint(method, path, handler));
    }

    @Override
    public void doStart() {
        javalin = Javalin.create(config -> pendingRoutes.forEach(config.routes::addEndpoint)).start(4567);
        pendingRoutes.clear();
        logger.info("REST service started on port 4567: {}", javalin);
    }

    @Override
    protected void doStop() {
        javalin.stop();
    }
}
