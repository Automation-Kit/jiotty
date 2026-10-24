package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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
import net.yudichev.jiotty.common.rest.HttpStatuses;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.Calendars;
import net.yudichev.jiotty.common.time.calendar.CalendarService.CalendarsResult.NotYetAuthenticated;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;
import static net.yudichev.jiotty.common.rest.HttpStatuses.FORBIDDEN_403;
import static net.yudichev.jiotty.common.rest.HttpStatuses.GONE_410;
import static net.yudichev.jiotty.common.rest.HttpStatuses.UNAUTHORIZED_401;
import static net.yudichev.jiotty.common.rest.RestClients.newClient;
import static net.yudichev.jiotty.common.rest.RestClients.shutdownNow;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.createResponseFailure;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.errorReason;
import static net.yudichev.jiotty.connector.google.calendar.GoogleCalendarApi.reportCredentialRejection;

@SuppressWarnings({"FieldAccessNotGuarded", "NonFinalGuard"}) // @GuardedBy("executor") marks executor confinement, which IntelliJ's lock analysis cannot see
public class GoogleCalendarService extends BaseLifecycleComponent implements CalendarService {
    /// What [#syncCalendarList] does, completing its failure messages.
    @VisibleForTesting
    static final String CALENDAR_LIST_SYNC = "sync the Google calendar list";
    @VisibleForTesting
    static final String RATE_LIMIT_EXCEEDED_REASON = "rateLimitExceeded";
    @VisibleForTesting
    static final String USER_RATE_LIMIT_EXCEEDED_REASON = "userRateLimitExceeded";

    private static final Logger logger = LogManager.getLogger(GoogleCalendarService.class);

    /// Calendar API 403 `reason` values that denote a transient rate/quota limit (retry with backoff) rather than a permanently-rejected credential — see
    /// [Handle API errors](https://developers.google.com/workspace/calendar/api/guides/errors). `dailyLimitExceeded` is the general Google quota reason,
    /// included defensively; a credential/permission problem is reported as a `401` or a different `403` reason, none of which appear here.
    private static final Set<String> TRANSIENT_403_REASONS =
            ImmutableSet.of(RATE_LIMIT_EXCEEDED_REASON, USER_RATE_LIMIT_EXCEEDED_REASON, "dailyLimitExceeded", "quotaExceeded");
    private static final CompletableFuture<@Nullable Void> COMPLETED = completedFuture(null);

    private final Provider<SchedulingExecutor> executorProvider;
    private final OAuth2TokenManager tokenManager;
    private final String redirectUri;
    private final Optional<String> authCode;
    private final Optional<String> codeVerifier;
    private final Duration timeout;
    private final String logSubjectId;
    private final ObservableValue<AuthState> authState;
    /// The current calendar set, kept up to date by incremental `calendarList` sync (see [#syncCalendarList]). An unchanged calendar keeps its existing
    /// [GoogleCalendar] instance across refreshes, so the consumer's identity-based change detection only fires on real changes.
    @GuardedBy("executor")
    private final Map<String, GoogleCalendar> calendarsById = new LinkedHashMap<>();
    /// Written by the token-state callback and read when a sync or an events fetch starts. `null` until the first token arrives.
    @GuardedBy("executor")
    private @Nullable String accessToken;
    /// `calendarList` sync token from the last successful sync, or `null` when a full list is needed (first sync, or after the token expired).
    @GuardedBy("executor")
    private @Nullable String calendarListSyncToken;
    private SchedulingExecutor executor;
    /// Runs the calendar-list syncs one after another, so each sync reads the [#calendarListSyncToken] and [#calendarsById] the previous one left.
    private SequentialOperationQueue syncQueue;
    private OkHttpClient httpClient;
    private GoogleCalendarApi api;
    private @Nullable Closeable tokenSubscription;

    @Inject
    public GoogleCalendarService(@Dependency Provider<SchedulingExecutor> executorProvider,
                                 @Dependency OAuth2TokenManager tokenManager,
                                 @RedirectUri String redirectUri,
                                 @AuthCode Optional<String> authCode,
                                 @CodeVerifier Optional<String> codeVerifier,
                                 @Timeout Duration timeout,
                                 @LogSubjectId String logSubjectId,
                                 TaskFailureReporter taskFailureReporter) {
        checkNotNull(taskFailureReporter);
        // Subscribers attach from the caller's thread, before start included, while the executor publishes.
        authState = ObservableValue.concurrent(new AuthState.TransientFailure("Initialising"),
                                               e -> taskFailureReporter.onTaskException("Delivering the Google Calendar authentication state", e));
        this.executorProvider = checkNotNull(executorProvider);
        this.tokenManager = checkNotNull(tokenManager);
        this.redirectUri = checkNotNull(redirectUri);
        this.authCode = checkNotNull(authCode);
        this.codeVerifier = checkNotNull(codeVerifier);
        checkArgument(timeout.isPositive(), "timeout must be positive, but was %s", timeout);
        this.timeout = timeout;
        this.logSubjectId = checkNotNull(logSubjectId);
    }

    @Override
    protected void doStart() {
        executor = executorProvider.get();
        syncQueue = new SequentialOperationQueue(executor);
        httpClient = createHttpClient();
        api = new GoogleCalendarApi(httpClient, logSubjectId);
        // Cache the latest access token for the requests and re-publish the token manager's state as our auth state (hiding the raw token).
        tokenSubscription = tokenManager.subscribeToAccessTokenState(state -> executor.tryExecute("onTokenState", () -> ifNotStopped(() -> {
            if (state instanceof AuthState.Success(String authInfo)) {
                accessToken = authInfo;
                authState.accept(AuthState.Success.AUTHENTICATED);
            } else {
                authState.accept(state);
            }
        })));
        // A freshly-supplied auth code means the user has just logged in; exchange it. Otherwise the token manager refreshes the persisted token on its own.
        authCode.ifPresent(code -> tokenManager.onNewAuthCode(code, redirectUri, codeVerifier));
    }

    @Override
    protected void doStop() {
        // Inline on whichever thread stops this service: closing the subscription and shutting the client are safe from any thread.
        closeSafelyIfNotNull(logger, tokenSubscription, () -> shutdownNow(httpClient));
    }

    /// The client every Calendar API call goes through. Overridden in tests to supply a mock returning canned API responses.
    @VisibleForTesting
    OkHttpClient createHttpClient() {
        return newClient(timeout);
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return authState.subscribe(consumer);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        return tokenManager.revoke();
    }

    @Override
    public CompletableFuture<CalendarsResult> retrieveCalendars() {
        //noinspection Convert2MethodRef — a method reference would read syncQueue, null before start, ahead of the started check
        return submitWhileStarted(sync -> syncQueue.submit(sync), this::syncCalendarList);
    }

    private CompletableFuture<CalendarsResult> syncCalendarList() {
        // Not authenticated yet — the auth-code exchange has not produced a token.
        if (accessToken == null) {
            return completedFuture(NotYetAuthenticated.INSTANCE);
        }
        return syncCalendarList(accessToken);
    }

    /// Refreshes [#calendarsById] from the Calendar API: in full while [#calendarListSyncToken] is `null`, otherwise only what changed, with one full resync
    /// after the sync token expires. Starts on [#executor]; the pages are fetched off it and applied back on it.
    ///
    /// @param token the access token every request of this sync carries, and so the one to invalidate if the API rejects it
    private CompletableFuture<CalendarsResult> syncCalendarList(String token) {
        String syncToken = calendarListSyncToken;
        // Held until the last page arrives: the pages come in off the executor that confines calendarsById, and a listing failing part-way must leave
        // calendarsById as it was.
        List<CalendarListEntry> entries = new ArrayList<>(calendarsById.size());
        CompletableFuture<CalendarList> listing = api.listCalendars(token, Optional.ofNullable(syncToken), page -> {
            List<CalendarListEntry> items = page.getItems();
            if (items != null) {
                entries.addAll(items);
            }
        });
        return executor.handleWhile(this::isStarted,
                                    listing,
                                    "Applying the Google calendar list",
                                    (lastPage, failure) -> failure == null
                                                           ? completedFuture(applySync(syncToken, entries, lastPage))
                                                           : onSyncFailure(token, syncToken, failure));
    }

    /// @param syncToken the token the listing continued from, or `null` for a full listing, which replaces every calendar
    private CalendarsResult applySync(@Nullable String syncToken, List<CalendarListEntry> entries, CalendarList lastPage) {
        if (syncToken == null) {
            calendarsById.clear();
        }
        for (CalendarListEntry entry : entries) {
            // A full listing leaves hidden calendars out and an incremental one reports them, so a hidden entry is removed here as a deleted one is.
            if (Boolean.TRUE.equals(entry.getDeleted()) || Boolean.TRUE.equals(entry.getHidden())) {
                calendarsById.remove(entry.getId());
            } else {
                String id = entry.getId();
                calendarsById.put(id, new GoogleCalendar(api,
                                                         executor,
                                                         this::currentAccessToken,
                                                         this::isStarted,
                                                         this::invalidateRejectedCredential,
                                                         id,
                                                         calendarName(entry),
                                                         logSubjectId));
            }
        }
        // only the last page carries nextSyncToken
        String nextSyncToken = lastPage.getNextSyncToken();
        if (nextSyncToken != null) {
            calendarListSyncToken = nextSyncToken;
        }
        return new Calendars(ImmutableList.copyOf(calendarsById.values()));
    }

    private static String calendarName(CalendarListEntry entry) {
        String override = entry.getSummaryOverride();
        if (override != null) {
            return override;
        }
        String summary = entry.getSummary();
        return summary != null ? summary : entry.getId();
    }

    /// @param syncToken the token the listing continued from, or `null` for a full listing, to which a {@value HttpStatuses#GONE_410} is a failure like any
    ///                  other
    private CompletableFuture<CalendarsResult> onSyncFailure(String token, @Nullable String syncToken, Throwable failure) {
        Optional<HttpResponseException> httpFailureInChain = HttpResponseException.findIn(failure);
        if (httpFailureInChain.isEmpty()) {
            return failedFuture(new RuntimeException("Failed to " + CALENDAR_LIST_SYNC, failure));
        }
        HttpResponseException httpFailure = httpFailureInChain.get();
        if (httpFailure.statusCode() == GONE_410 && syncToken != null) {
            logger.info("[{}] Google calendar list sync token expired; performing a full resync", logSubjectId);
            calendarListSyncToken = null;
            return syncCalendarList(token);
        }
        if (isPermanentAuthError(httpFailure)) {
            // The API rejects the credential — revoked access, a deleted client project, or the API not being enabled — even though the
            // token itself may still refresh. Invalidate it (escalating to a permanent auth failure) rather than retrying this call indefinitely against a
            // credential that will never work.
            return failedFuture(reportCredentialRejection(httpFailure, CALENDAR_LIST_SYNC, token, this::invalidateRejectedCredential));
        }
        return failedFuture(createResponseFailure(httpFailure, CALENDAR_LIST_SYNC));
    }

    /// Whether a Calendar API error means the credential is permanently rejected and the user must re-authenticate: an unauthorised response, or a forbidden
    /// one that is not a transient rate/quota limit. A deleted client project, revoked access, or the API not being enabled all surface as a forbidden response
    /// without a rate-limit reason, whereas a rate limit is transient and must not trigger re-authentication.
    private static boolean isPermanentAuthError(HttpResponseException e) {
        return switch (e.statusCode()) {
            case UNAUTHORIZED_401 -> true;
            // Treat a 403 without a recognised reason as permanent: better to prompt re-auth than to retry a genuinely-broken credential forever.
            case FORBIDDEN_403 -> errorReason(e).map(reason -> !TRANSIENT_403_REASONS.contains(reason)).orElse(true);
            default -> false;
        };
    }

    /// Invalidates `rejectedToken` in [#tokenManager], recording `rejection` as the cause. Runs on [#executor], in a task that has found this service started.
    private void invalidateRejectedCredential(String rejectedToken, String rejection) {
        tokenManager.invalidate(rejectedToken, rejection);
    }

    /// @return the access token, or a future that never completes once this service has stopped
    private CompletableFuture<String> currentAccessToken() {
        return executor.handleWhile(this::isStarted, COMPLETED, "Reading the Google Calendar access token", (_, _) -> {
            assert accessToken != null : "a calendar exists only once an access token has arrived";
            return completedFuture(accessToken);
        });
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface RedirectUri {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface AuthCode {
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface CodeVerifier {
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
}
