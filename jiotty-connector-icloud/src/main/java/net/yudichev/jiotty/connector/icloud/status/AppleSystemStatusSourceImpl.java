package net.yudichev.jiotty.connector.icloud.status;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.backoff.RetryableOperationExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Append;
import net.yudichev.jiotty.common.lang.Json;
import net.yudichev.jiotty.common.lang.MutableReference;
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.rest.UnsuccessfulResponseDetailExtractor;
import net.yudichev.jiotty.common.time.CurrentDateTimeProvider;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessage;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.rest.RestClients.callSuppressingResponseBody;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdownNow;

/// An [AppleSystemStatusSource] that reads the data file behind Apple's public system status page, repeating a failed exchange with backoff.
public class AppleSystemStatusSourceImpl extends BaseLifecycleComponent implements AppleSystemStatusSource {
    private static final Logger logger = LogManager.getLogger(AppleSystemStatusSourceImpl.class);

    @VisibleForTesting
    static final Request STATUS_REQUEST = new Request.Builder().url("https://www.apple.com/support/systemstatus/data/system_status_en_GB.js").build();
    @VisibleForTesting
    static final String CALENDAR_SERVICE_NAME = "iCloud Calendar";
    @VisibleForTesting
    static final String SIGN_IN_SERVICE_NAME = "iCloud Account & Sign In";
    @VisibleForTesting
    static final String APPLE_ACCOUNT_SERVICE_NAME = "Apple Account";
    private static final StringFormattable LOGGABLE_URL = appendable -> Append.to(appendable, "Apple system status");
    /// The services whose events affect reading an iCloud calendar, as the file names them.
    private static final Set<String> RELEVANT_SERVICE_NAMES = ImmutableSet.of(CALENDAR_SERVICE_NAME, SIGN_IN_SERVICE_NAME, APPLE_ACCOUNT_SERVICE_NAME);

    private final CurrentDateTimeProvider currentDateTimeProvider;
    private final Duration timeout;
    private final RetryableOperationExecutor retryableOperationExecutor;
    private OkHttpClient httpClient;

    @Inject
    public AppleSystemStatusSourceImpl(CurrentDateTimeProvider currentDateTimeProvider,
                                       @Timeout Duration timeout,
                                       @Dependency RetryableOperationExecutor retryableOperationExecutor) {
        this.currentDateTimeProvider = checkNotNull(currentDateTimeProvider);
        checkArgument(timeout.isPositive(), "timeout must be positive, but was %s", timeout);
        this.timeout = timeout;
        this.retryableOperationExecutor = checkNotNull(retryableOperationExecutor);
    }

    @Override
    protected void doStart() {
        httpClient = createHttpClient();
    }

    @Override
    protected void doStop() {
        // Inline on whichever thread stops this source: shutting the client is safe from any thread.
        shutdownNow(httpClient);
    }

    /// Creates the client that sends the status request. The client fails a call when the body of its response is longer than the client's size limit.
    @VisibleForTesting
    OkHttpClient createHttpClient() {
        return newClient(timeout).newBuilder()
                                 .addInterceptor(new BodySizeLimitInterceptor(1024 * 1024)) // the file is about 9 KB
                                 .build();
    }

    @Override
    public CompletableFuture<IcloudCalendarStatus> readIcloudCalendarStatus() {
        return submitWhileStarted(this::requestStatusFileWithRetries, this::requestStatusFile)
                .thenApply(this::toStatus)
                .exceptionally(failure -> {
                    logger.info("Apple's system status is unreadable: {}", humanReadableMessageFormattable(failure));
                    return IcloudCalendarStatus.UNREADABLE;
                });
    }

    /// Runs `exchangeSupplier` with backoff after each failed attempt, logging one line for each run of attempts that fail with the same message.
    private CompletableFuture<String> requestStatusFileWithRetries(Supplier<CompletableFuture<String>> exchangeSupplier) {
        var lastLoggedFailureMessage = new MutableReference<@Nullable String>();
        return retryableOperationExecutor.withBackOffAndRetry("apple.systemStatus", exchangeSupplier, (delayMs, failure) -> {
            String failureMessage = humanReadableMessage(failure);
            if (!failureMessage.equals(lastLoggedFailureMessage.get())) {
                lastLoggedFailureMessage.set(failureMessage);
                logger.info("Reading Apple's system status failed, retrying in {} ms: {}", delayMs, failureMessage);
            }
        });
    }

    /// @return the body of the status file
    private CompletableFuture<String> requestStatusFile() {
        // 0, the RestClients retry count: retryableOperationExecutor repeats a failed exchange with backoff, and a second retry loop inside it would
        // multiply the attempts
        return callSuppressingResponseBody(httpClient.newCall(STATUS_REQUEST), body -> body, 0, LOGGABLE_URL, UnsuccessfulResponseDetailExtractor.NONE);
    }

    private IcloudCalendarStatus toStatus(String body) {
        StatusFile statusFile = Json.parse(body, StatusFile.class);
        if (statusFile.services() == null) {
            logger.info("Apple's system status is unreadable: it lists no services");
            return IcloudCalendarStatus.UNREADABLE;
        }
        long nowMillis = currentDateTimeProvider.currentInstant().toEpochMilli();
        boolean relevantServiceListed = false;
        for (Service service : statusFile.services()) {
            if (RELEVANT_SERVICE_NAMES.contains(service.serviceName())) {
                relevantServiceListed = true;
                if (service.events() != null) {
                    for (Event event : service.events()) {
                        if (event.isOngoingAt(nowMillis)) {
                            return IcloudCalendarStatus.ONGOING_EVENT;
                        }
                    }
                }
            }
        }
        if (relevantServiceListed) {
            return IcloudCalendarStatus.NO_ONGOING_EVENT;
        }
        logger.info("Apple's system status is unreadable: it lists none of the services whose events are read");
        return IcloudCalendarStatus.UNREADABLE;
    }

    /// @param services the services that the file lists, or `null` when the file has no such list
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatusFile(@Nullable List<Service> services) {
    }

    /// @param serviceName the service's name, or `null` when the entry has none
    /// @param events      the events that Apple reports for the service, or `null` when the entry has no such list
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Service(@Nullable String serviceName, @Nullable List<Event> events) {
    }

    /// @param epochStartDate when the event started or will start, in milliseconds since the epoch, or `null` when the entry has no start
    /// @param epochEndDate   when the event ended or will end, in milliseconds since the epoch, or `null` for an event with no end yet
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Event(@Nullable Long epochStartDate, @Nullable Long epochEndDate) {
        public boolean isOngoingAt(long epochMillis) {
            return epochStartDate != null && epochStartDate <= epochMillis && (epochEndDate == null || epochMillis < epochEndDate);
        }
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Timeout {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
