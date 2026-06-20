package net.yudichev.jiotty.user.push;

import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.persistence.varstore.VarStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

public final class PushDeviceStoreImpl extends BaseLifecycleComponent implements PushDeviceStore {
    private static final Logger logger = LogManager.getLogger(PushDeviceStoreImpl.class);
    private static final String STORE_KEY = "push.devices";
    private static final TypeToken<Map<String, PushDeviceRecord>> STORE_TYPE = new TypeToken<>() {};

    private final ExecutorFactory executorFactory;
    private final VarStore varStore;
    private SchedulingExecutor executor;

    @Inject
    public PushDeviceStoreImpl(ExecutorFactory executorFactory, @Dependency VarStore varStore) {
        this.executorFactory = checkNotNull(executorFactory);
        this.varStore = checkNotNull(varStore);
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor("push-device-store");
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, executor);
    }

    @Override
    public CompletableFuture<Void> upsert(PushDeviceRecord record) {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            Map<String, PushDeviceRecord> records = loadMutable();
            records.put(record.deviceId(), record);
            save(records);
        }));
    }

    @Override
    public CompletableFuture<Void> remove(String deviceId) {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            Map<String, PushDeviceRecord> records = loadMutable();
            if (records.remove(deviceId) != null) {
                save(records);
            }
        }));
    }

    @Override
    public CompletableFuture<Void> pruneByToken(String token) {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            Map<String, PushDeviceRecord> records = loadMutable();
            if (records.values().removeIf(record -> record.token().equals(token))) {
                save(records);
            }
        }));
    }

    @Override
    public CompletableFuture<List<PushDeviceRecord>> list() {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> ImmutableList.copyOf(loadMutable().values())));
    }

    private Map<String, PushDeviceRecord> loadMutable() {
        return varStore.readValueEncrypted(STORE_TYPE, STORE_KEY)
                       .<Map<String, PushDeviceRecord>>map(LinkedHashMap::new)
                       .orElseGet(LinkedHashMap::new);
    }

    private void save(Map<String, PushDeviceRecord> records) {
        if (records.isEmpty()) {
            varStore.clearValue(STORE_KEY);
        } else {
            // Push tokens let the holder send notifications to a user's device — persist encrypted at rest.
            varStore.saveValueEncrypted(STORE_KEY, records);
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    public @interface Dependency {
    }
}
