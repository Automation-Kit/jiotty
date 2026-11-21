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
import net.yudichev.jiotty.common.lang.StringFormattable;
import net.yudichev.jiotty.common.observer.ObservableValue;
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
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Maps.newLinkedHashMapWithExpectedSize;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.lang.HumanReadableExceptionMessage.humanReadableMessageFormattable;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdownNow;
import static net.yudichev.jiotty.common.security.LogRedaction.redacted;

@SuppressWarnings({"FieldAccessNotGuarded", "NonFinalGuard"}) // @GuardedBy("executor") marks executor confinement, which IntelliJ's lock analysis cannot see
public class IcloudCalendarService extends BaseLifecycleComponent implements CalendarService {
    private static final Logger logger = LogManager.getLogger(IcloudCalendarService.class);

    @VisibleForTesting
    static final String AUTH_STATE_DELIVERY_TASK = "Delivering the iCloud Calendar authentication state";
    @VisibleForTesting
    static final String AUTHENTICATION_RETRY_TASK = "Retrying the iCloud Calendar authentication";
    private static final String UNAUTHORISED_DESCRIPTION = "Authorisation failed: HTTP " + UNAUTHORIZED_401;
    @VisibleForTesting
    static final AuthState UNAUTHORISED = new AuthState.PermanentFailure(UNAUTHORISED_DESCRIPTION);
    private static final AuthState NO_USABLE_RESPONSE = new AuthState.TransientFailure("No usable response");

    private final Provider<SchedulingExecutor> executorProvider;
    private final String authorisation;
    private final Duration timeout;
    private final String logSubjectId;
    private final TaskFailureReporter taskFailureReporter;
    private final ObservableValue<AuthState> apiKeyState;
    /// The calendars the latest retrieval returned, by the collection each describes. An unchanged calendar keeps its existing [IcloudCalendar] instance
    /// across retrievals, so the consumer's identity-based change detection only fires on real changes.
    @GuardedBy("executor")
    private Map<CalendarCollection, IcloudCalendar> calendarsByCollection = ImmutableMap.of();
    /// The server's rejection of the credentials that suspended this service, or `null` while it is not suspended. A suspended service sends the requests
    /// of [#retryAuthentication()] and fails every other call with this rejection as the cause.
    @GuardedBy("executor")
    private @Nullable HttpResponseException suspensionCause;
    /// How many suspensions have ended. Each request keeps the count as it was when sent; a higher count when the request is rejected means a suspension
    /// has ended in between.
    @GuardedBy("executor")
    private int endedSuspensionCount;
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
        this.taskFailureReporter = checkNotNull(taskFailureReporter);
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
                                 .addInterceptor(new CredentialRejectionAndServerErrorLoggingInterceptor(logSubjectId))
                                 .build();
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return apiKeyState.subscribe(consumer);
    }

    @Override
    public CompletableFuture<CalendarsResult> retrieveCalendars() {
        return submitRetrieval(false).thenCompose(outcome -> switch (outcome) {
            case RetrievalOutcome.Retrieved(CalendarsResult result) -> completedFuture(result);
            case RetrievalOutcome.Failed(Throwable failure) -> failedFuture(failure);
        });
    }

    /// Lists the calendars once more, also while this service is suspended, and publishes one of these outcomes:
    /// - an [AuthState.Success] when the server accepts the credentials, which ends the suspension;
    /// - an [AuthState.PermanentFailure] when the server rejects the credentials, which starts or renews the suspension;
    /// - an [AuthState.TransientFailure] for any other failure.
    @Override
    public void retryAuthentication() {
        submitRetrieval(true).whenComplete((outcome, failure) -> {
            if (failure != null) {
                // the retry failed before reaching onRetrievalFailure, which publishes an AuthState for every failed retry
                apiKeyState.accept(NO_USABLE_RESPONSE);
                taskFailureReporter.onTaskException(AUTHENTICATION_RETRY_TASK, failure);
            } else if (outcome instanceof RetrievalOutcome.Failed(Throwable retrievalFailure) && logger.isDebugEnabled()) {
                logger.debug("[{}] The authentication retry failed: {}", logSubjectId, humanReadableMessageFormattable(retrievalFailure));
            }
        });
    }

    private CompletableFuture<RetrievalOutcome> submitRetrieval(boolean authenticationRetry) {
        //noinspection Convert2MethodRef — a method reference would read retrievalQueue, null before start, ahead of the started check
        return submitWhileStarted(retrieval -> retrievalQueue.submit(retrieval), () -> retrieve(authenticationRetry));
    }

    /// @param authenticationRetry whether the retrieval is a [#retryAuthentication()]
    private CompletableFuture<RetrievalOutcome> retrieve(boolean authenticationRetry) {
        if (suspensionCause != null && !authenticationRetry) {
            return completedFuture(createSuspendedOutcome());
        }
        int endedSuspensionCountWhenSent = endedSuspensionCount;
        return executor.handleWhile(this::isStarted,
                                    calDavClient.discoverCalendarHome(),
                                    "Listing the iCloud calendars",
                                    (calendarHome, failure) -> failure == null
                                                               ? listCalendars(calendarHome, authenticationRetry, endedSuspensionCountWhenSent)
                                                               : completedFuture(onRetrievalFailure(failure,
                                                                                                    authenticationRetry,
                                                                                                    endedSuspensionCountWhenSent)));
    }

    private CompletableFuture<RetrievalOutcome> listCalendars(HttpUrl calendarHome, boolean authenticationRetry, int endedSuspensionCountWhenSent) {
        return executor.handleWhile(this::isStarted,
                                    calDavClient.listCalendars(calendarHome),
                                    "Collecting the iCloud calendars",
                                    (collections, failure) -> completedFuture(failure == null
                                                                              ? onCalendarsListed(collections, authenticationRetry)
                                                                              : onRetrievalFailure(failure,
                                                                                                   authenticationRetry,
                                                                                                   endedSuspensionCountWhenSent)));
    }

    private RetrievalOutcome onCalendarsListed(List<CalendarCollection> collections, boolean authenticationRetry) {
        if (suspensionCause != null) {
            if (!authenticationRetry) {
                return createSuspendedOutcome();
            }
            suspensionCause = null;
            endedSuspensionCount++;
            logger.info("[{}] The server accepts the credentials again", logSubjectId);
        }
        apiKeyState.accept(AuthState.Success.AUTHENTICATED);
        return new RetrievalOutcome.Retrieved(toCalendars(collections));
    }

    private CalendarsResult toCalendars(List<CalendarCollection> collections) {
        var calendars = ImmutableList.<Calendar>builderWithExpectedSize(collections.size());
        Map<CalendarCollection, IcloudCalendar> newCalendarsByCollection = newLinkedHashMapWithExpectedSize(collections.size());
        for (CalendarCollection collection : collections) {
            IcloudCalendar calendar = calendarsByCollection.get(collection);
            if (calendar == null) {
                calendar = new IcloudCalendar(collection.href(), collection.url(), collection.displayName(), this::readCalendarData, logSubjectId);
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

    /// Passes a rejection of the credentials to [#onRejection]. For any other failure, publishes an [AuthState.TransientFailure] when either of these holds:
    /// - the failed retrieval is a [#retryAuthentication()];
    /// - the failure carries an HTTP status and this service is not suspended.
    ///
    /// @return what the retrieval fails with, which is:
    ///
    ///         - for a rejection, the [CalendarAuthorisationException] that [#onRejection] returns;
    ///         - for any other failure of a retrieval that is not a [#retryAuthentication()], while this service is suspended, the
    ///           [CalendarAuthorisationException] of the rejection that suspended this service;
    ///         - otherwise `failure`, wrapped in a [RuntimeException]
    private RetrievalOutcome.Failed onRetrievalFailure(Throwable failure, boolean authenticationRetry, int endedSuspensionCountWhenSent) {
        HttpResponseException httpFailure = HttpResponseException.findIn(failure).orElse(null);
        if (isRejection(httpFailure)) {
            return new RetrievalOutcome.Failed(onRejection(httpFailure, authenticationRetry, endedSuspensionCountWhenSent));
        }
        if (authenticationRetry) {
            apiKeyState.accept(httpFailure == null ? NO_USABLE_RESPONSE : createTransientFailure(httpFailure));
        } else if (suspensionCause != null) {
            return createSuspendedOutcome();
        } else if (httpFailure != null) {
            apiKeyState.accept(createTransientFailure(httpFailure));
        }
        return new RetrievalOutcome.Failed(new RuntimeException("Failed to fetch calendar list", failure));
    }

    private static AuthState createTransientFailure(HttpResponseException httpFailure) {
        return new AuthState.TransientFailure("HTTP " + httpFailure.statusCode());
    }

    /// @return what a retrieval other than a [#retryAuthentication()] fails with while this service is suspended
    private RetrievalOutcome.Failed createSuspendedOutcome() {
        assert suspensionCause != null : "only called while suspended";
        return new RetrievalOutcome.Failed(createAuthorisationException(suspensionCause));
    }

    private <T> CompletableFuture<T> readCalendarData(HttpUrl calendarUrl,
                                                      StringFormattable loggableUrl,
                                                      Instant from,
                                                      Instant to,
                                                      Function<? super List<String>, ? extends T> parser) {
        return executor.handleWhile(this::isStarted,
                                    whenStarted(),
                                    "Starting an iCloud calendar events read",
                                    // the failure is always null: whenStarted() returns a completed future or one that never completes
                                    (_, _) -> readCalendarDataUnlessSuspended(calendarUrl, loggableUrl, from, to, parser));
    }

    /// @return a future already completed while this service is started, and one that never completes once it has stopped
    private CompletableFuture<@Nullable Void> whenStarted() {
        return whenNotLifecycling(() -> isStartedPlain() ? completedFuture(null) : new CompletableFuture<>());
    }

    private <T> CompletableFuture<T> readCalendarDataUnlessSuspended(HttpUrl calendarUrl,
                                                                     StringFormattable loggableUrl,
                                                                     Instant from,
                                                                     Instant to,
                                                                     Function<? super List<String>, ? extends T> parser) {
        if (suspensionCause != null) {
            return failedFuture(createAuthorisationException(suspensionCause));
        }
        int endedSuspensionCountWhenSent = endedSuspensionCount;
        return executor.handleWhile(this::isStarted,
                                    calDavClient.fetchCalendarData(calendarUrl, loggableUrl, from, to),
                                    "Reading the iCloud calendar events",
                                    (calendarData, failure) -> failure == null
                                                               ? completedFuture(parser.apply(calendarData))
                                                               : failedFuture(onEventsReadFailure(failure, endedSuspensionCountWhenSent)));
    }

    /// Passes a rejection of the credentials to [#onRejection].
    ///
    /// @return what the events read fails with
    private Throwable onEventsReadFailure(Throwable failure, int endedSuspensionCountWhenSent) {
        HttpResponseException httpFailure = HttpResponseException.findIn(failure).orElse(null);
        return isRejection(httpFailure)
               ? onRejection(httpFailure, false, endedSuspensionCountWhenSent)
               : failure;
    }

    /// @param httpFailure the HTTP failure to classify, or `null` for a failure that carries no HTTP status, which is never a rejection
    /// @return whether the server rejected the credentials
    private static boolean isRejection(@Nullable HttpResponseException httpFailure) {
        return httpFailure != null && httpFailure.statusCode() == UNAUTHORIZED_401;
    }

    /// Suspends this service and publishes the rejection when the rejected request is either of:
    /// - a [#retryAuthentication()];
    /// - a request rejected while this service is not suspended, when no suspension has ended since the request was sent.
    ///
    /// @param endedSuspensionCountWhenSent how many suspensions had ended when the rejected request was sent
    /// @return what the rejected request fails with
    private CalendarAuthorisationException onRejection(HttpResponseException rejection, boolean authenticationRetry, int endedSuspensionCountWhenSent) {
        if (authenticationRetry || (suspensionCause == null && endedSuspensionCountWhenSent == endedSuspensionCount)) {
            if (suspensionCause == null) {
                logger.info("[{}] The server rejected the credentials: no request is sent until an authentication retry succeeds", logSubjectId);
            }
            suspensionCause = rejection;
            apiKeyState.accept(UNAUTHORISED);
        }
        return createAuthorisationException(rejection);
    }

    private static CalendarAuthorisationException createAuthorisationException(HttpResponseException rejection) {
        return new CalendarAuthorisationException(UNAUTHORISED_DESCRIPTION, rejection);
    }

    /// The outcome of a calendar retrieval, created after the [AuthState] due for that outcome has been published.
    private sealed interface RetrievalOutcome {
        /// @param result the calendars that the server listed
        record Retrieved(CalendarsResult result) implements RetrievalOutcome {
            public Retrieved {
                checkNotNull(result);
            }
        }

        /// @param failure what the retrieval fails with
        record Failed(Throwable failure) implements RetrievalOutcome {
            public Failed {
                checkNotNull(failure);
            }
        }
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
