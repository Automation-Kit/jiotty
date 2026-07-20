package net.yudichev.jiotty.world.homelocation;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
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
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

final class HomeLocationServiceImpl extends BaseLifecycleComponent implements HomeLocationService {
    private static final Logger logger = LogManager.getLogger(HomeLocationServiceImpl.class);

    private final UIServer uiServer;
    private final Provider<SchedulingExecutor> executorProvider;
    private final Listeners<LatLon> listeners = new Listeners<>();
    private SchedulingExecutor executor;
    private Closeable optionRegistration;
    private @Nullable LatLon location;

    @Inject
    public HomeLocationServiceImpl(UIServer uiServer, @Dependency Provider<SchedulingExecutor> executorProvider) {
        this.uiServer = checkNotNull(uiServer);
        this.executorProvider = checkNotNull(executorProvider);
    }

    @Override
    public Closeable addListener(Consumer<LatLon> listener) {
        return listeners.addListener(executor, () -> Optional.ofNullable(location), listener);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        optionRegistration = uiServer.registerOption(new LocationOption(executor,
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
        });
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, optionRegistration);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
