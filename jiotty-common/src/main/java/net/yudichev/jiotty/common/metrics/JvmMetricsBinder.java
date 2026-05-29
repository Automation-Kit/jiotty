package net.yudichev.jiotty.common.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

final class JvmMetricsBinder extends BaseLifecycleComponent {
    private static final Logger logger = LogManager.getLogger(JvmMetricsBinder.class);

    private final MeterRegistry registry;
    private final List<AutoCloseable> binderCloseables = new ArrayList<>();

    @Inject
    JvmMetricsBinder(MeterRegistry registry) {
        this.registry = checkNotNull(registry, "registry");
    }

    @Override
    protected void doStart() {
        List<MeterBinder> binders = List.of(new JvmGcMetrics(),
                                            new JvmMemoryMetrics(),
                                            new JvmThreadMetrics(),
                                            new ClassLoaderMetrics(),
                                            new ProcessorMetrics(),
                                            new UptimeMetrics());
        for (MeterBinder binder : binders) {
            binder.bindTo(registry);
            if (binder instanceof AutoCloseable autoCloseable) {
                binderCloseables.add(autoCloseable);
            }
        }
        logger.info("Bound {} JVM/process Micrometer binders to {}", binders.size(), registry.getClass().getSimpleName());
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, binderCloseables);
        binderCloseables.clear();
    }
}
