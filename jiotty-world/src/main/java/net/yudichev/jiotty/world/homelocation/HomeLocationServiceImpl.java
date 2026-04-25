package net.yudichev.jiotty.world.homelocation;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.user.ui.UIServer;
import net.yudichev.jiotty.user.ui.options.LocationOption;
import net.yudichev.jiotty.user.ui.options.OptionMeta;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

final class HomeLocationServiceImpl extends BaseLifecycleComponent implements HomeLocationService {
    private static final Logger logger = LogManager.getLogger(HomeLocationServiceImpl.class);

    private final UIServer uiServer;
    private final ExecutorFactory executorFactory;
    private final Listeners<LatLon> listeners = new Listeners<>();
    private SchedulingExecutor executor;
    private List<Closeable> resources;
    private @Nullable LatLon location;

    @Inject
    public HomeLocationServiceImpl(UIServer uiServer, ExecutorFactory executorFactory) {
        this.uiServer = checkNotNull(uiServer);
        this.executorFactory = checkNotNull(executorFactory);
    }

    @Override
    public Closeable addListener(Consumer<LatLon> listener) {
        return listeners.addListener(executor, () -> Optional.ofNullable(location), listener);
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("HomeLocation");
        resources = List.of(executor,
                            uiServer.registerOption(new LocationOption(executor,
                                                                       OptionMeta.<LatLon>builder()
                                                                                 .setTabName("Misc")
                                                                                 .setKey("homeLocation")
                                                                                 .setLabel("Home Location")
                                                                                 .setSensitive(true)
                                                                                 .build()) {
                                @Override
                                public LatLon onChanged() {
                                    LatLon v = value();
                                    location = v;
                                    listeners.notify(v);
                                    return v;
                                }
                            }))
                        .reversed();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, resources);
    }
}
