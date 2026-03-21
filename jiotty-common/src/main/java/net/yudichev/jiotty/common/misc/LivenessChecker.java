package net.yudichev.jiotty.common.misc;

import jakarta.annotation.Nullable;
import net.yudichev.jiotty.common.async.Scheduler;
import net.yudichev.jiotty.common.lang.BaseIdempotentCloseable;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.PackagePrivateImmutablesStyle;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.immutables.value.Value;
import org.immutables.value.Value.Immutable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static net.yudichev.jiotty.common.lang.Locks.inLock;

public final class LivenessChecker extends BaseIdempotentCloseable implements Consumer<Object> {
    private static final int RUN_HISTORY_SIZE = 20;
    private static final Logger logger = LogManager.getLogger(LivenessChecker.class);
    private final Lock lock = new ReentrantLock();
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Duration dataMissingThreshold;
    private final Closeable schedule;
    private final Deque<TimestampedData> lastTimestampedData = new LinkedList<>();
    private final Consumer<Collection<TimestampedData>> errorHandler;
    private final BiConsumer<TimestampedData, TimestampedData> recoveryHandler;
    @Nullable
    private Instant dataLastArrived;

    public LivenessChecker(String name, Scheduler executor, CurrentDateTimeProvider currentDateTimeProvider, Duration dataMissingThreshold) {
        this(executor,
             currentDateTimeProvider,
             dataMissingThreshold,
             instants -> logger.error("{}: data missing: last data {}", name, instants),
             (previousData, newData) -> logger.error("Recovered after {}; last data: {}, now: {}",
                                                     Duration.between(previousData.timestamp(), newData.timestamp()), previousData, newData));
    }

    LivenessChecker(Scheduler executor,
                    CurrentDateTimeProvider currentDateTimeProvider,
                    Duration dataMissingThreshold,
                    Consumer<Collection<TimestampedData>> errorHandler,
                    BiConsumer<TimestampedData, TimestampedData> recoveryHandler) {
        dataLastArrived = currentDateTimeProvider.currentInstant();
        this.currentDateTimeProvider = currentDateTimeProvider;
        this.dataMissingThreshold = dataMissingThreshold;
        this.errorHandler = checkNotNull(errorHandler);
        this.recoveryHandler = checkNotNull(recoveryHandler);
        schedule = executor.scheduleAtFixedRate(dataMissingThreshold, () -> inLock(lock, this::checkLiveness));
    }

    @Override
    public void accept(Object data) {
        inLock(lock, () -> {
            Instant now = currentDateTimeProvider.currentInstant();
            var newData = TimestampedData.of(now, data);
            if (dataLastArrived == null) {
                // recovered
                TimestampedData previousData = lastTimestampedData.peekLast();
                if (previousData != null) {
                    recoveryHandler.accept(previousData, newData);
                }
            }
            dataLastArrived = now;
            lastTimestampedData.add(newData);
            if (lastTimestampedData.size() >= RUN_HISTORY_SIZE) {
                lastTimestampedData.poll();
            }
        });
    }

    @Override
    protected void doClose() {
        schedule.close();
    }

    private void checkLiveness() {
        if (dataLastArrived != null && dataLastArrived.plus(dataMissingThreshold).isBefore(currentDateTimeProvider.currentInstant())) {
            errorHandler.accept(lastTimestampedData);
            dataLastArrived = null;
        }
    }

    @Immutable
    @PackagePrivateImmutablesStyle
    interface BaseTimestampedData {
        @Value.Parameter
        Instant timestamp();

        @Value.Parameter
        Object data();
    }
}
