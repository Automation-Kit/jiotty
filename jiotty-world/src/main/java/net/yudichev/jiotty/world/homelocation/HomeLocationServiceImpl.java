package net.yudichev.jiotty.world.homelocation;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.geo.LatLon;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.user.ui.OptionMeta;
import net.yudichev.jiotty.user.ui.TextOption;
import net.yudichev.jiotty.user.ui.UIServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

final class HomeLocationServiceImpl extends BaseLifecycleComponent implements HomeLocationService {
    private static final Logger logger = LoggerFactory.getLogger(HomeLocationServiceImpl.class);

    private final UIServer uiServer;
    private final ExecutorFactory executorFactory;
    private final Listeners<LatLon> listeners = new Listeners<>();
    private SchedulingExecutor executor;
    private List<Closeable> resources;
    private @Nullable Double lat;
    private @Nullable Double lon;
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
        resources = List.of(executor = executorFactory.createSingleThreadedSchedulingExecutor("HomeLocation"),
                            uiServer.registerOption(new NumberOption("lat", "Home Latitude", this::onLatUpdate)),
                            uiServer.registerOption(new NumberOption("lon", "Home Longitude", this::onLonUpdate)))
                        .reversed();
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, resources);
    }

    private void onLatUpdate(double lat) {
        this.lat = lat;
        updateListeners();
    }

    private void onLonUpdate(double lon) {
        this.lon = lon;
        updateListeners();
    }

    private void updateListeners() {
        if (lat != null && lon != null) {
            location = new LatLon(lat, lon);
        } else {
            location = null;
        }
        listeners.notify(location);
    }

    private class NumberOption extends TextOption {
        private final DoubleConsumer valueConsumer;

        public NumberOption(String id, String label, DoubleConsumer valueConsumer) {
            super(HomeLocationServiceImpl.this.executor, new OptionMeta<>("Misc", "homeLocation." + id, label, null));
            this.valueConsumer = checkNotNull(valueConsumer);
        }

        @Override
        public String onChanged() {
            Optional<Double> newValue;
            try {
                Optional<String> valueStr = getValue();
                newValue = valueStr.map(Double::parseDouble);
            } catch (NumberFormatException e) {
                //noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
                throw new RuntimeException("Invalid number");
            }
            newValue.ifPresent(valueConsumer::accept);
            return newValue.map(Objects::toString).orElse(null);
        }
    }
}
