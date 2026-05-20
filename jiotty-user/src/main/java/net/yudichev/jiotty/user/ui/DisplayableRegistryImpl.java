package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.common.lang.throttling.ThrottlingConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.Closeable.noop;
import static net.yudichev.jiotty.user.ui.Bindings.UIExecutor;

public final class DisplayableRegistryImpl extends BaseLifecycleComponent implements DisplayableRegistry {
    private static final Logger logger = LogManager.getLogger(DisplayableRegistryImpl.class);
    private static final Duration UPDATE_THROTTLE = Duration.ofSeconds(1);

    private final Map<String, Displayable> displayablesById = new LinkedHashMap<>();
    private final Listeners<Displayable> updateListeners = new Listeners<>();
    private final Listeners<Displayable> registrationListeners = new Listeners<>();
    private final Provider<SchedulingExecutor> executorProvider;

    private SchedulingExecutor executor;

    @Inject
    public DisplayableRegistryImpl(@UIExecutor Provider<SchedulingExecutor> executorProvider) {
        this.executorProvider = checkNotNull(executorProvider, "executorProvider");
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
    }

    @Override
    public Closeable register(Displayable displayable) {
        checkNotNull(displayable, "displayable");
        return whenStartedAndNotLifecycling(() -> {
            checkArgument(displayablesById.putIfAbsent(displayable.getId(), displayable) == null,
                          "Displayable with id '%s' is already registered", displayable.getId());
            Closeable dataSubscription;
            @Nullable ThrottlingConsumer<Void> throttle;
            if (displayable.supportsData()) {
                throttle = new ThrottlingConsumer<>(executor, UPDATE_THROTTLE, _ -> updateListeners.notify(displayable));
                dataSubscription = displayable.subscribeForUpdates(() -> throttle.accept(null));
            } else {
                dataSubscription = noop();
                throttle = null;
            }
            logger.info("Registered displayable {} with title {}", displayable, displayable.getDisplayName());
            registrationListeners.notify(displayable);
            return idempotent(() -> whenStartedAndNotLifecycling(() -> {
                if (displayablesById.remove(displayable.getId(), displayable)) {
                    Closeable.closeSafelyIfNotNull(logger, throttle, dataSubscription);
                    logger.info("Unregistered displayable {} with title {}", displayable, displayable.getDisplayName());
                }
            }));
        });
    }

    @Override
    public Optional<Displayable> find(String id) {
        return whenStartedAndNotLifecycling(() -> Optional.ofNullable(displayablesById.get(id)));
    }

    @Override
    public Collection<Displayable> all() {
        return whenStartedAndNotLifecycling(() -> ImmutableList.copyOf(displayablesById.values()));
    }

    @Override
    public Closeable subscribeToUpdates(Consumer<Displayable> onUpdated) {
        return updateListeners.addListener(checkNotNull(onUpdated, "onUpdated"));
    }

    @Override
    public Closeable subscribeToRegistrations(Consumer<Displayable> onRegistered) {
        checkNotNull(onRegistered, "onRegistered");
        return whenStartedAndNotLifecycling(() -> {
            Closeable subscription = registrationListeners.addListener(onRegistered);
            for (Displayable displayable : displayablesById.values()) {
                onRegistered.accept(displayable);
            }
            return subscription;
        });
    }
}
