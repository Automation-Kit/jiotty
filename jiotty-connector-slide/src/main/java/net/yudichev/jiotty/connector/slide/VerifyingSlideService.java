package net.yudichev.jiotty.connector.slide;

import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeIfNotNull;
import static net.yudichev.jiotty.common.lang.CompletableFutures.completedFuture;
import static net.yudichev.jiotty.connector.slide.Bindings.ServiceExecutor;

final class VerifyingSlideService implements SlideService {
    private static final Logger logger = LogManager.getLogger(VerifyingSlideService.class);
    private static final Duration POSITION_POLL_PERIOD = Duration.ofSeconds(1);
    private static final Duration POSITION_VERIFY_TIMEOUT = Duration.ofSeconds(30);

    private final SlideService delegate;
    private final Provider<SchedulingExecutor> executorProvider;
    private final double positionTolerance;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Map<Long, TargetPosition> targetPositionBySlideId = new ConcurrentHashMap<>();

    @Inject
    VerifyingSlideService(@Delegate SlideService delegate,
                          @ServiceExecutor Provider<SchedulingExecutor> executorProvider,
                          @Tolerance double positionTolerance,
                          CurrentDateTimeProvider currentDateTimeProvider) {
        this.delegate = checkNotNull(delegate);
        this.executorProvider = checkNotNull(executorProvider);
        this.positionTolerance = positionTolerance;
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
    }

    @Override
    public CompletableFuture<SlideInfo> getSlideInfo(long slideId, Executor executor) {
        return delegate.getSlideInfo(slideId, executor);
    }

    @Override
    public CompletableFuture<Void> setSlidePosition(long slideId, double position, Executor executor) {
        var targetPosition = new TargetPosition(slideId, position);
        TargetPosition previousPosition = targetPositionBySlideId.put(slideId, targetPosition);
        boolean wasStillMoving = false;
        if (previousPosition != null) {
            wasStillMoving = previousPosition.inProgress();
            previousPosition.cancel();
        }
        if (wasStillMoving) {
            logger.debug("Slide {}: was moving, force new position {}", slideId, position);
            return delegate.setSlidePosition(slideId, position, executor)
                           .thenCompose(_ -> targetPosition.await());
        } else {
            return delegate.getSlideInfo(slideId)
                           .thenCompose(slideInfo -> {
                               double currentPosition = slideInfo.position();
                               boolean withinTolerance = withinTolerance(currentPosition, position);
                               logger.debug("Slide {}: current position {}, target position {}, within tolerance of {}: {}",
                                            slideId, currentPosition, position, positionTolerance, withinTolerance);
                               return withinTolerance ? completedFuture() : delegate.setSlidePosition(slideId, position, executor);
                           })
                           .thenCompose(_ -> targetPosition.await());
        }
    }

    private boolean withinTolerance(double currentPosition, double targetPosition) {
        return Math.abs(currentPosition - targetPosition) < positionTolerance;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Delegate {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Tolerance {
    }

    private final class TargetPosition {
        private final long slideId;
        private final double targetPosition;
        private final Instant deadline;
        private final CompletableFuture<Void> result;
        private Closeable schedule;

        private TargetPosition(long slideId, double targetPosition) {
            this.slideId = slideId;
            this.targetPosition = targetPosition;
            result = new CompletableFuture<>();
            deadline = currentDateTimeProvider.currentInstant().plus(POSITION_VERIFY_TIMEOUT);
            logger.debug("Slide {}: deadline to reach position {} is {}", slideId, targetPosition, deadline);
        }

        public CompletableFuture<Void> await() {
            logger.debug("Side {}: will poll for position in {}", slideId, POSITION_POLL_PERIOD);
            schedule = executorProvider.get().schedule(POSITION_POLL_PERIOD, this::pollPosition);
            return result;
        }

        private void pollPosition() {
            delegate.getSlideInfo(slideId)
                    .whenComplete((slideInfo, e) -> {
                        if (e == null) {
                            double currentPosition = slideInfo.position();
                            logger.debug("Slide {}: current pos {}, target pos {}", slideId, currentPosition, targetPosition);
                            if (withinTolerance(currentPosition, targetPosition)) {
                                logger.debug("Slide {} reached satisfiable position", slideId);
                                result.complete(null);
                            } else if (currentDateTimeProvider.currentInstant().isAfter(deadline)) {
                                result.completeExceptionally(new RuntimeException(
                                        "Timed out verifying position of slide " + slideInfo + " after " + POSITION_VERIFY_TIMEOUT + "+, target position " +
                                        targetPosition + ", current position " + currentPosition));
                            } else {
                                await();
                            }
                        } else {
                            result.completeExceptionally(new RuntimeException("Failed to verify position of slide " + slideInfo + ": status poll failed", e));
                        }
                    });
        }

        public void cancel() {
            closeIfNotNull(schedule);
            result.complete(null);
        }

        public boolean inProgress() {
            return !result.isDone();
        }
    }
}
