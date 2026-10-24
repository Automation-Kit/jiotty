package net.yudichev.jiotty.connector.icloud.calendar;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.async.SequentialOperationQueue;
import net.yudichev.jiotty.common.async.TaskFailureReporter;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.ObservableValue;
import net.yudichev.jiotty.common.rest.HttpResponseException;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarAuthorisationException;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.Calendars;
import net.yudichev.jiotty.connector.icloud.calendar.CalDavClient.CalendarCollection;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdownNow;
import static net.yudichev.jiotty.common.security.LogRedaction.redacted;

@SuppressWarnings({"FieldAccessNotGuarded", "NonFinalGuard"}) // @GuardedBy("executor") marks executor confinement, which IntelliJ's lock analysis cannot see
public class IcloudCalendarService extends BaseLifecycleComponent implements CalendarService {
    @VisibleForTesting
    static final String AUTH_STATE_DELIVERY_TASK = "Delivering the iCloud Calendar authentication state";
    private static final Logger logger = LogManager.getLogger(IcloudCalendarService.class);
    private static final String UNAUTHORISED_DESCRIPTION = "Authorisation failed: HTTP " + UNAUTHORIZED_401;
    private static final AuthState UNAUTHORISED = new AuthState.PermanentFailure(UNAUTHORISED_DESCRIPTION);

    private final Provider<SchedulingExecutor> executorProvider;
    private final String authorisation;
    private final Duration timeout;
    private final String logSubjectId;
    private final ObservableValue<AuthState> apiKeyState;
    /// The calendars the latest retrieval returned, by the collection each describes. An unchanged calendar keeps its existing [IcloudCalendar] instance
    /// across retrievals, so the consumer's identity-based change detection only fires on real changes.
    @GuardedBy("executor")
    private Map<CalendarCollection, IcloudCalendar> calendarsByCollection = ImmutableMap.of();
    private SchedulingExecutor executor;
    /// Runs the retrievals one after another, so the auth state each publishes is the outcome of the latest.
    private SequentialOperationQueue retrievalQueue;
    private OkHttpClient httpClient;
    private CalDavClient calDavClient;

    @Inject
    public IcloudCalendarService(@Dependency Provider<SchedulingExecutor> executorProvider,
                                 @Username String username,
                                 @Password String password,
                                 @Timeout Duration timeout,
                                 @LogSubjectId String logSubjectId,
                                 TaskFailureReporter taskFailureReporter) {
        checkNotNull(taskFailureReporter);
        // Callers subscribe from their own threads, also before start(), while the executor publishes.
        apiKeyState = ObservableValue.concurrent(new AuthState.TransientFailure("Initialising"),
                                                 e -> taskFailureReporter.onTaskException(AUTH_STATE_DELIVERY_TASK, e));
        this.executorProvider = checkNotNull(executorProvider);
        // HTTP Basic with the account's app-specific password
        authorisation = Credentials.basic(username, password);
        checkArgument(timeout.isPositive(), "timeout must be positive, but was %s", timeout);
        this.timeout = timeout;
        this.logSubjectId = checkNotNull(logSubjectId);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        retrievalQueue = new SequentialOperationQueue(executor);
        httpClient = createHttpClient();
        // Wraps the client this start creates.
        calDavClient = new CalDavClient(httpClient, authorisation, logSubjectId);
    }

    @Override
    protected void doStop() {
        // Inline on whichever thread stops this service: shutting the client is safe from any thread.
        shutdownNow(httpClient);
    }

    /// Follows no redirect, which would carry the account's credentials to wherever it pointed and turn a {@value CalDavClient#REPORT} into a `GET`.
    /// Overridden in tests to supply a mock client returning canned responses.
    @VisibleForTesting
    OkHttpClient createHttpClient() {
        return newClient(timeout).newBuilder()
                                 .followRedirects(false)
                                 .followSslRedirects(false)
                                 .build();
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return apiKeyState.subscribe(consumer);
    }

    @Override
    public CompletableFuture<CalendarsResult> retrieveCalendars() {
        //noinspection Convert2MethodRef — a method reference would read retrievalQueue, null before start, ahead of the started check
        return submitWhileStarted(retrieval -> retrievalQueue.submit(retrieval), this::retrieve);
    }

    private CompletableFuture<CalendarsResult> retrieve() {
        return executor.handleWhile(this::isStarted,
                                    calDavClient.discoverCalendarHome(),
                                    "Listing the iCloud calendars",
                                    (calendarHome, failure) -> failure == null ? onCalendarHomeDiscovered(calendarHome) : onDiscoveryFailure(failure));
    }

    private CompletableFuture<CalendarsResult> onCalendarHomeDiscovered(HttpUrl calendarHome) {
        apiKeyState.accept(AuthState.Success.AUTHENTICATED);
        return executor.handleWhile(this::isStarted,
                                    calDavClient.listCalendars(calendarHome),
                                    "Collecting the iCloud calendars",
                                    (collections, failure) -> failure == null ? completedFuture(toCalendars(collections)) : failedFuture(failure));
    }

    private CalendarsResult toCalendars(List<CalendarCollection> collections) {
        var calendars = ImmutableList.<Calendar>builderWithExpectedSize(collections.size());
        Map<CalendarCollection, IcloudCalendar> newCalendarsByCollection = newLinkedHashMapWithExpectedSize(collections.size());
        for (CalendarCollection collection : collections) {
            IcloudCalendar calendar = calendarsByCollection.get(collection);
            if (calendar == null) {
                calendar = new IcloudCalendar(collection.href(),
                                              collection.url(),
                                              collection.displayName(),
                                              this::startedCalDavClient,
                                              executor,
                                              this::isStarted,
                                              logSubjectId);
            }
            newCalendarsByCollection.put(collection, calendar);
            calendars.add(calendar);
            if (logger.isDebugEnabled()) {
                logger.debug("[{}] Retrieved calendar: {} -> {}", logSubjectId, redacted(collection.href()), redacted(collection.displayName()));
            }
        }
        calendarsByCollection = newCalendarsByCollection;
        return new Calendars(calendars.build());
    }

    private CompletableFuture<CalendarsResult> onDiscoveryFailure(Throwable failure) {
        Optional<HttpResponseException> httpFailure = HttpResponseException.findIn(failure);
        if (httpFailure.isPresent()) {
            int statusCode = httpFailure.get().statusCode();
            if (statusCode == UNAUTHORIZED_401) {
                apiKeyState.accept(UNAUTHORISED);
                return failedFuture(new CalendarAuthorisationException(UNAUTHORISED_DESCRIPTION, httpFailure.get()));
            }
            apiKeyState.accept(new AuthState.TransientFailure("HTTP " + statusCode));
        }
        return failedFuture(new RuntimeException("Failed to fetch calendar list", failure));
    }

    /// @return a future already completed with the client while this service is started, and one that never completes once it has stopped
    private CompletableFuture<CalDavClient> startedCalDavClient() {
        return whenNotLifecycling(() -> isStartedPlain() ? completedFuture(calDavClient) : new CompletableFuture<>());
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Username {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Password {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Timeout {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface LogSubjectId {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
