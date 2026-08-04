package net.yudichev.jiotty.user.ui;

import com.google.common.collect.ImmutableList;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Listeners;
import net.yudichev.jiotty.user.ui.options.Option;
import net.yudichev.jiotty.user.ui.options.OptionPersistence;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.forCloseables;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;

public final class OptionRegistryImpl extends BaseLifecycleComponent implements OptionRegistry {
    private static final Logger logger = LogManager.getLogger(OptionRegistryImpl.class);

    private final Map<String, Option<?>> optionsByKey = new LinkedHashMap<>();
    private final List<Closeable> optionsPersistenceRegistrations = new ArrayList<>();
    private final Listeners<Void> snapshotChangeListeners = new Listeners<>();
    private final OptionPersistence persistence;

    @Inject
    public OptionRegistryImpl(OptionPersistence persistence) {
        this.persistence = checkNotNull(persistence, "persistence");
    }

    @Override
    public Closeable register(Option<?> option) {
        checkNotNull(option, "option");
        return whenStartedAndNotLifecycling(() -> {
            checkArgument(!optionsByKey.containsKey(option.meta().key()), "Option for key %s already registered: %s", option.meta().key(), option);
            persistence.load(option);
            optionsByKey.put(option.meta().key(), option);
            Closeable persistenceRegistration = option.addChangeListener(theOption -> {
                persistence.save(theOption);
                snapshotChangeListeners.notify(null);
            });
            optionsPersistenceRegistrations.add(persistenceRegistration);
            logger.info("Registered option {}", option.meta().key());
            snapshotChangeListeners.notify(null);
            // An owner commonly unregisters from its own teardown, which can run after this registry has stopped, so this takes the lifecycle lock and does
            // nothing once stopped. Notifying the snapshot listeners then reaches subscribers whose executor has already terminated, and this registry's own
            // state is unreachable by that point.
            return idempotent(() -> whenNotLifecycling(() -> {
                if (isStarted() && optionsByKey.remove(option.meta().key(), option)) {
                    Closeable.closeIfNotNull(persistenceRegistration);
                    optionsPersistenceRegistrations.remove(persistenceRegistration);
                    logger.info("Unregistered option {}", option.meta().key());
                    snapshotChangeListeners.notify(null);
                }
            }));
        });
    }

    @Override
    public Optional<Option<?>> find(String key) {
        return whenStartedAndNotLifecycling(() -> Optional.ofNullable(optionsByKey.get(key)));
    }

    @Override
    public Collection<Option<?>> all() {
        return whenStartedAndNotLifecycling(() -> ImmutableList.copyOf(optionsByKey.values()));
    }

    @Override
    public Closeable subscribeToSnapshotChanges(Runnable onChanged) {
        checkNotNull(onChanged, "onChanged");
        return whenStartedAndNotLifecycling(() -> {
            Closeable subscription = snapshotChangeListeners.addListener(_ -> onChanged.run());
            onChanged.run();
            return subscription;
        });
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, forCloseables(optionsPersistenceRegistrations));
    }
}
