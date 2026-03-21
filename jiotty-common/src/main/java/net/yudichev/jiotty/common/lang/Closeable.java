package net.yudichev.jiotty.common.lang;

import org.apache.logging.log4j.Logger;

import java.util.Collection;

import static com.google.common.collect.ImmutableList.copyOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Arrays.asList;
import static net.yudichev.jiotty.common.lang.CompositeException.runForAll;
import static net.yudichev.jiotty.common.lang.MoreThrowables.asUnchecked;

@SuppressWarnings("OverloadedVarargsMethod")
public interface Closeable extends AutoCloseable {
    @SuppressWarnings("OverloadedMethodsWithSameNumberOfParameters")
    static Closeable forCloseables(Closeable... closeables) {
        return forCloseables(asList(closeables));
    }

    @SuppressWarnings("OverloadedMethodsWithSameNumberOfParameters")
    static Closeable forCloseables(AutoCloseable... closeables) {
        return forCloseables(asList(closeables));
    }

    static Closeable forActions(Runnable... actions) {
        return forActions(copyOf(actions));
    }

    static Closeable forCloseables(Collection<? extends AutoCloseable> closeables) {
        return forActions(closeables.stream()
                                    .<Runnable>map(closeable -> () -> asUnchecked(closeable::close))
                                    .collect(toImmutableList()));
    }

    static Closeable forActions(Collection<? extends Runnable> actions) {
        return idempotent(() -> runForAll(actions, Runnable::run));
    }

    static Closeable idempotent(AutoCloseable closeable) {
        return new BaseIdempotentCloseable() {
            @Override
            protected void doClose() {
                asUnchecked(closeable::close);
            }
        };
    }

    static Closeable noop() {
        return () -> {};
    }

    static void closeIfNotNull(AutoCloseable closeable) {
        if (closeable != null) {
            asUnchecked(closeable::close);
        }
    }

    static void closeIfNotNull(Closeable... closeables) {
        for (Closeable closeable : closeables) {
            closeIfNotNull(closeable);
        }
    }

    static void closeIfNotNull(AutoCloseable... closeable) {
        for (AutoCloseable autoCloseable : closeable) {
            closeIfNotNull(autoCloseable);
        }
    }

    static void closeSafelyIfNotNull(Logger logger, Closeable... closeables) {
        for (Closeable closeable : closeables) {
            closeSafelyIfNotNull(logger, closeable);
        }
    }

    static void closeSafelyIfNotNull(Logger logger, AutoCloseable closeable) {
        if (closeable != null) {
            try {
                asUnchecked(closeable::close);
            } catch (RuntimeException e) {
                logger.warn("Failed to close {}", closeable, e);
            }
        }
    }

    static void closeSafelyIfNotNull(Logger logger, AutoCloseable... closeables) {
        for (AutoCloseable closeable : closeables) {
            closeSafelyIfNotNull(logger, closeable);
        }
    }

    static void closeSafelyIfNotNull(Logger logger, Iterable<? extends AutoCloseable> closeables) {
        for (AutoCloseable closeable : closeables) {
            closeSafelyIfNotNull(logger, closeable);
        }
    }

    @Override
    void close();

    default void closeSafely(Logger logger) {
        closeSafelyIfNotNull(logger, this);
    }
}
