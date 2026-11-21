package net.yudichev.jiotty.user.push;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.observer.Listeners;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class PushDeviceStoreImpl extends BaseLifecycleComponent implements PushDeviceStore {
    @VisibleForTesting
    static final String STORE_KEY = "push.devices";
    private static final TypeToken<Map<String, PushDeviceRecord>> STORE_TYPE = new TypeToken<>() {};

    private static final Logger logger = LogManager.getLogger(PushDeviceStoreImpl.class);

    private final Provider<SchedulingExecutor> executorProvider;
    private final VarStore varStore;
    private final Listeners<List<PushDeviceRecord>> deviceListeners = new Listeners<>();
    private SchedulingExecutor executor;

    @Inject
    public PushDeviceStoreImpl(@Dependency Provider<SchedulingExecutor> executorProvider, @Dependency VarStore varStore) {
        this.executorProvider = checkNotNull(executorProvider);
        this.varStore = checkNotNull(varStore);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
    }

    /// Stores `record` under its device id, keeping the push token unique across the store: a device that re-registers under a fresh device id — which it
    /// does whenever its secure storage is reset — keeps one record, so each notification reaches it once.
    @Override
    public CompletableFuture<Void> upsert(PushDeviceRecord record) {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            Map<String, PushDeviceRecord> recordsByDeviceId = loadMutable();
            recordsByDeviceId.values().removeIf(existing -> existing.token().equals(record.token()));
            recordsByDeviceId.put(record.deviceId(), record);
            saveAndNotify(recordsByDeviceId);
        }));
    }

    @Override
    public CompletableFuture<Void> remove(String deviceId) {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            Map<String, PushDeviceRecord> recordsByDeviceId = loadMutable();
            if (recordsByDeviceId.remove(deviceId) != null) {
                saveAndNotify(recordsByDeviceId);
            }
        }));
    }

    @Override
    public CompletableFuture<Void> pruneByToken(String token) {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            Map<String, PushDeviceRecord> recordsByDeviceId = loadMutable();
            if (recordsByDeviceId.values().removeIf(record -> record.token().equals(token))) {
                saveAndNotify(recordsByDeviceId);
            }
        }));
    }

    @Override
    public CompletableFuture<List<PushDeviceRecord>> list() {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> listStored(varStore)));
    }

    @Override
    public Closeable subscribe(Consumer<? super List<PushDeviceRecord>> listener) {
        checkNotNull(listener, "listener");
        return whenStartedAndNotLifecycling(() -> deviceListeners.addListener(executor,
                                                                              () -> Optional.of(listStored(varStore)),
                                                                              devices -> deliver(listener, devices)));
    }

    private static void deliver(Consumer<? super List<PushDeviceRecord>> listener, List<PushDeviceRecord> devices) {
        try {
            listener.accept(devices);
        } catch (RuntimeException e) {
            logger.info("A subscriber failed", e);
        }
    }

    /// Backs [PushDeviceStore#listStored(VarStore)].
    static List<PushDeviceRecord> listStored(VarStore varStore) {
        return ImmutableList.copyOf(load(varStore).values());
    }

    private Map<String, PushDeviceRecord> loadMutable() {
        return new LinkedHashMap<>(load(varStore));
    }

    private static Map<String, PushDeviceRecord> load(VarStore varStore) {
        return varStore.readValueEncrypted(STORE_TYPE, STORE_KEY).orElse(ImmutableMap.of());
    }

    private void saveAndNotify(Map<String, PushDeviceRecord> recordsByDeviceId) {
        save(recordsByDeviceId);
        deviceListeners.notify(ImmutableList.copyOf(recordsByDeviceId.values()));
    }

    private void save(Map<String, PushDeviceRecord> recordsByDeviceId) {
        if (recordsByDeviceId.isEmpty()) {
            varStore.clearValue(STORE_KEY);
        } else {
            // Push tokens let the holder send notifications to a user's device — persist encrypted at rest.
            varStore.saveValueEncrypted(STORE_KEY, recordsByDeviceId);
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }
}
