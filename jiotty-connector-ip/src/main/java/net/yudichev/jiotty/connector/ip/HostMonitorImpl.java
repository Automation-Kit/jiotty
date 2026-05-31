package net.yudichev.jiotty.connector.ip;

import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.DispatchedConsumer;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.ConcurrentDeduplicatingConsumer;
import net.yudichev.jiotty.common.lang.StabilisingConsumer;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.stream.Collectors.joining;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.lang.Closeable.idempotent;
import static net.yudichev.jiotty.common.lang.EqualityComparator.referenceEquality;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.connector.ip.HostMonitor.Status.DOWN;
import static net.yudichev.jiotty.connector.ip.HostMonitor.Status.UP;
import static net.yudichev.jiotty.connector.ip.HostMonitorImpl.OsPingProcessExecutor.OS.LINUX;
import static net.yudichev.jiotty.connector.ip.HostMonitorImpl.OsPingProcessExecutor.OS.MACOS;

final class HostMonitorImpl extends BaseLifecycleComponent implements HostMonitor {
    private static final Logger logger = LogManager.getLogger(HostMonitorImpl.class);

    private final ExecutorFactory executorFactory;
    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final List<String> hostnames;
    private final String name;
    private final InetAddressResolver inetAddressResolver;
    private final Duration tolerance;
    private final Duration periodBetweenPings;

    private final CopyOnWriteArrayList<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private final CompositePoller poller;
    private final PingProcessExecutor pingProcessExecutor;

    private Consumer<Status> statusStabiliser;
    private volatile SchedulingExecutor executor;
    private @Nullable Instant lastSuccessfulPing;
    private @Nullable Status currentStatus;
    private @Nullable Status currentStableStatus;
    private int lastReachableHostIdx;
    private Closeable pingSchedule = Closeable.noop();

    @Inject
    HostMonitorImpl(ExecutorFactory executorFactory,
                    CurrentDateTimeProvider currentDateTimeProvider,
                    @Hostnames List<String> hostnames,
                    @Name String name,
                    @Tolerance Duration tolerance) {
        this(executorFactory, currentDateTimeProvider, hostnames, name, tolerance, InetAddress::getByName, new OsPingProcessExecutor());
    }

    HostMonitorImpl(ExecutorFactory executorFactory,
                    CurrentDateTimeProvider currentDateTimeProvider,
                    List<String> hostnames,
                    String name,
                    Duration tolerance,
                    InetAddressResolver inetAddressFactory,
                    PingProcessExecutor pingProcessExecutor) {
        this.executorFactory = checkNotNull(executorFactory);
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        this.hostnames = ImmutableList.copyOf(hostnames);
        this.name = checkNotNull(name);
        inetAddressResolver = checkNotNull(inetAddressFactory);
        this.pingProcessExecutor = checkNotNull(pingProcessExecutor);
        checkState(tolerance.compareTo(Duration.ofSeconds(5)) >= 0,
                   "tolerance must be >= 5 seconds, but was %s", tolerance);
        this.tolerance = checkNotNull(tolerance);
        periodBetweenPings = tolerance.dividedBy(10);
        poller = new CompositePoller(new JavaAddressReachabilityPoller(), new PingPoller());
    }

    @Override
    public Closeable addListener(Consumer<Status> statusConsumer, Executor executor) {
        return whenStartedAndNotLifecycling(() -> {
            Consumer<Status> consumer = new DispatchedConsumer<>(new ConcurrentDeduplicatingConsumer<>(referenceEquality(), statusConsumer), executor);
            listeners.add(consumer);
            this.executor.execute(() -> {
                if (currentStableStatus != null) {
                    notifyListener(consumer);
                }
            });
            return idempotent(() -> listeners.remove(consumer));
        });
    }

    @Override
    protected void doStart() {
        logger.info("Start monitoring {} ({}) with tolerance {}", name, hostnames, tolerance);
        executor = executorFactory.createSingleThreadedSchedulingExecutor("host-monitor-" + name);
        statusStabiliser = new ConcurrentDeduplicatingConsumer<>(referenceEquality(),
                                                                 new StabilisingConsumer<>(executor, tolerance, this::onStableStatus));

        executor.execute(() -> {
            onStatus(UP, "Assume UP on startup");
            ping();
        });
    }

    @Override
    protected void doStop() {
        SchedulingExecutor executor = this.executor;
        //noinspection AssignmentToNull
        this.executor = null;
        // after this, provided everyone who uses executor on its thread checks for the nullness of the executor field, there will be no more tasks, meaning
        // that the task below is guaranteed to be the last one
        executor.execute(() -> {
            pingSchedule.close();
            currentStatus = null;
            lastSuccessfulPing = null;
        });
        closeSafelyIfNotNull(logger, executor);
    }

    private void onStableStatus(Status status) {
        logger.info("{} ({}) {}->{}", name, hostnames, currentStableStatus, status);
        currentStableStatus = status;
        listeners.forEach(this::notifyListener);
    }

    private void scheduleNextPing() {
        SchedulingExecutor executor = this.executor;
        if (executor != null) {
            pingSchedule = executor.schedule(periodBetweenPings, this::ping);
        }
    }

    private void ping() {
        List<String> unreachableReasons = new ArrayList<>(hostnames.size());
        for (int i = 0; i < hostnames.size(); i++) {
            int idx = (lastReachableHostIdx + i) % hostnames.size();
            String hostname = hostnames.get(idx);

            logger.debug("Ping {} ({})", name, hostname);

            Optional<String> pollError = poller.poll(hostname);
            if (pollError.isEmpty()) {
                lastSuccessfulPing = currentDateTimeProvider.currentInstant();
                lastReachableHostIdx = idx;
                logger.debug("{} is reachable via host {} ({}), lastSuccessfulPing={}", name, lastReachableHostIdx, hostname, lastSuccessfulPing);
                unreachableReasons.clear();
                onStatus(UP, "Reachable via " + hostname);
                //noinspection BreakStatement
                break;
            } else {
                unreachableReasons.add(hostname + ": " + pollError.get());
            }
        }
        if (!unreachableReasons.isEmpty()) {
            onUnreachable(unreachableReasons.toString());
        }
        scheduleNextPing();
    }

    private void onUnreachable(String why) {
        logger.debug("{} ({}) is unreachable: {}, lastSuccessfulPing={}", name, hostnames, why, lastSuccessfulPing);
        onStatus(DOWN, why);
    }

    private void onStatus(Status status, String description) {
        if (status != currentStatus) {
            logger.info("{} ({}) provisionally {}->{} ({})", name, hostnames, currentStatus, status, description);
            currentStatus = status;
            SchedulingExecutor executor = this.executor;
            if (executor != null) {
                // executor is used by the stabiliser to schedule more events; this cannot be done if we have stopped, or started stopping
                statusStabiliser.accept(currentStatus);
            }
        }
    }

    private void notifyListener(Consumer<Status> consumer) {
        logger.debug("Notify consumer {} about status {}", consumer, currentStableStatus);
        consumer.accept(currentStableStatus);
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Hostnames {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Name {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Tolerance {
    }

    interface InetAddressResolver {
        InetAddress resolve(String hostname) throws UnknownHostException;
    }

    interface HostPoller {
        Optional<String> poll(String hostname);
    }

    interface PingProcessExecutor {
        Process execute(String hostname) throws IOException;
    }

    private class JavaAddressReachabilityPoller implements HostPoller {

        @Override
        public Optional<String> poll(String hostname) {
            try {
                InetAddress inetAddress = inetAddressResolver.resolve(hostname);
                logger.debug("{} resolved to {}", hostname, inetAddress);
                if (inetAddress.isReachable(5000)) {
                    return Optional.empty();
                }
                return Optional.of("unreachable");
            } catch (IOException e) {
                return Optional.of(humanReadableMessage(e));
            }
        }
    }

    static class OsPingProcessExecutor implements PingProcessExecutor {
        static final OS os;

        static {
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) {
                os = OS.WINDOWS;
            } else if (osName.contains("osx") || osName.contains("os x")) {
                os = MACOS;
            } else if (osName.contains("nix") || osName.contains("aix") || osName.contains("nux")) {
                os = LINUX;
            } else {
                throw new UnsupportedOperationException("Unable to determine operating system kind from os.name " + osName);
            }
        }

        @SuppressWarnings("CallToRuntimeExec")
        @Override
        public Process execute(String hostname) throws IOException {
            var cmdLine = os.cmdLineBuilder.apply(hostname);
            if (logger.isDebugEnabled()) {
                logger.debug("Executing {}", Arrays.toString(cmdLine));
            }
            return Runtime.getRuntime().exec(cmdLine);
        }

        enum OS {
            WINDOWS(hostname -> new String[]{"ping", "-n 1", "-w 5000", hostname}),
            MACOS(hostname -> new String[]{"ping", "-c 1", "-t 5", hostname}),
            LINUX(hostname -> new String[]{"ping", "-c 1", "-W 5", hostname});

            private final Function<String, String[]> cmdLineBuilder;

            OS(Function<String, String[]> cmdLineBuilder) {
                this.cmdLineBuilder = checkNotNull(cmdLineBuilder);
            }
        }
    }

    private class PingPoller implements HostPoller {

        @Override
        public Optional<String> poll(String hostname) {
            Process process;
            try {
                process = pingProcessExecutor.execute(hostname);
                try (var stdOutReader = process.inputReader();
                     var stdErrReader = process.errorReader()) {
                    boolean processFinished = process.waitFor(10, TimeUnit.SECONDS);
                    if (!processFinished) {
                        return Optional.of("ping process timed out");
                    }
                    var stdOut = stdOutReader.lines().collect(joining("; "));
                    if (process.exitValue() != 0) {
                        var stdErr = stdErrReader.lines().collect(joining("; "));
                        return Optional.of("ping failed; stdout: " + stdOut + (stdErr.isEmpty() ? "" : ", stdErr: " + stdErr));
                    }
                    logger.debug("{} pinged successfully: {}", hostname, stdOut);
                    return Optional.empty();
                }
            } catch (IOException e) {
                logger.debug("{} ping execution failed", hostname, e);
                return Optional.of("ping execution failed: " + humanReadableMessage(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    }

    @SuppressWarnings("ClassCanBeRecord")
    private static class CompositePoller implements HostPoller {
        private final ImmutableList<HostPoller> delegates;

        CompositePoller(HostPoller... delegates) {
            checkArgument(delegates.length > 0);
            this.delegates = ImmutableList.copyOf(delegates);
        }

        @Override
        public Optional<String> poll(String hostname) {
            StringBuilder errorBuilder = null;
            for (int i = 0; i < delegates.size(); i++) {
                HostPoller delegate = delegates.get(i);
                var pollResult = delegate.poll(hostname);
                if (pollResult.isEmpty()) {
                    return pollResult;
                } else {
                    if (errorBuilder == null) {
                        errorBuilder = new StringBuilder(pollResult.get().length() * delegates.size());
                    }
                    if (!errorBuilder.isEmpty()) {
                        errorBuilder.append("; ");
                    }
                    errorBuilder.append("Poller ").append(delegate.getClass().getSimpleName()).append(" returned: ").append(pollResult.get());
                }
            }
            checkState(errorBuilder != null);
            return Optional.of(errorBuilder.toString());
        }
    }
}