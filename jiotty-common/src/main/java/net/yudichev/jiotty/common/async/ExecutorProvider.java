package net.yudichev.jiotty.common.async;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

final class ExecutorProvider extends BaseLifecycleComponent implements Provider<SchedulingExecutor> {
    private static final Logger logger = LogManager.getLogger(ExecutorProvider.class);

    private final ExecutorFactory executorFactory;
    private final String threadName;
    private final String family;
    private final int maxQueueSize;
    private SchedulingExecutor executor;

    @Inject
    public ExecutorProvider(ExecutorFactory executorFactory, @ThreadName String threadName, @Family String family, @MaxQueueSize int maxQueueSize) {
        this.executorFactory = checkNotNull(executorFactory);
        this.threadName = checkNotNull(threadName);
        this.family = checkNotNull(family);
        this.maxQueueSize = maxQueueSize;
    }

    @Override
    public String name() {
        return String.format("%s @ %s (%s)", getClass().getSimpleName(), System.identityHashCode(this), threadName);
    }

    @Override
    public SchedulingExecutor get() {
        return executor;
    }

    @Override
    protected void doStart() {
        executor = executorFactory.createSingleThreadedSchedulingExecutor(threadName, family, maxQueueSize);
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, executor);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface ThreadName {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Family {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface MaxQueueSize {
    }
}
