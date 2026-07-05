package net.yudichev.jiotty.connector.google.calendar;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.async.ExecutorFactory;
import net.yudichev.jiotty.common.async.SchedulingExecutor;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.ObservableValue;
import net.yudichev.jiotty.common.security.AuthState;
import net.yudichev.jiotty.common.time.calendar.Calendar;
import net.yudichev.jiotty.common.time.calendar.CalendarService;
import net.yudichev.jiotty.security.OAuth2TokenManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.Closeable.closeSafelyIfNotNull;

class GoogleCalendarService extends BaseLifecycleComponent implements CalendarService {
    private static final Logger logger = LogManager.getLogger(GoogleCalendarService.class);
    private static final String APPLICATION_NAME = "jiotty";
    /// Re-emitted in place of the token manager's [AuthState.Success] so the raw access token is never exposed to subscribers.
    private static final AuthState AUTHENTICATED = new AuthState.Success("authenticated");
    /// Calendar API 403 `reason` values that denote a transient rate/quota limit (retry with backoff) rather than a permanently-rejected credential — see
    /// [Handle API errors](https://developers.google.com/workspace/calendar/api/guides/errors). `dailyLimitExceeded` is the general Google quota reason,
    /// included defensively; a credential/permission problem is reported as a `401` or a different `403` reason, none of which appear here.
    private static final Set<String> TRANSIENT_403_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded", "quotaExceeded");

    private final ExecutorFactory executorFactory;
    private final OAuth2TokenManager tokenManager;
    private final String redirectUri;
    private final Optional<String> authCode;
    private final Optional<String> codeVerifier;
    private final int timeoutMillis;
    private final String logSubjectId;
    private final ObservableValue<AuthState> authState = ObservableValue.concurrent(new AuthState.TransientFailure("Initialising"));
    /// The current calendar set, kept up to date by incremental `calendarList` sync (see [#syncCalendarList]). Confined to [#executor]: an unchanged calendar
    /// keeps its existing [GoogleCalendar] instance across refreshes, so the consumer's identity-based change detection only fires on real changes.
    private final Map<String, GoogleCalendar> calendarsById = new LinkedHashMap<>();
    /// Confined to [#executor]: written by the token-state callback (which marshals onto the executor in [#doStart]) and read by the request initializer, which
    /// runs during Google Calendar API calls that are themselves submitted to the executor. `null` until the first token arrives.
    private @Nullable String accessToken;
    /// `calendarList` sync token from the last successful sync, or `null` when a full list is needed (first sync, or after the token expired). Confined to
    /// [#executor].
    private @Nullable String calendarListSyncToken;
    private SchedulingExecutor executor;
    private com.google.api.services.calendar.Calendar calendarApi;
    private @Nullable Closeable tokenSubscription;

    @Inject
    public GoogleCalendarService(ExecutorFactory executorFactory,
                                 @Dependency OAuth2TokenManager tokenManager,
                                 @RedirectUri String redirectUri,
                                 @AuthCode Optional<String> authCode,
                                 @CodeVerifier Optional<String> codeVerifier,
                                 @Timeout Duration timeout,
                                 @LogSubjectId String logSubjectId) {
        this.executorFactory = checkNotNull(executorFactory);
        this.tokenManager = checkNotNull(tokenManager);
        this.redirectUri = checkNotNull(redirectUri);
        this.authCode = checkNotNull(authCode);
        this.codeVerifier = checkNotNull(codeVerifier);
        timeoutMillis = Math.toIntExact(checkNotNull(timeout).toMillis());
        this.logSubjectId = checkNotNull(logSubjectId);
    }

    @Override
    protected void doStart() {
        // Tag the executor thread with the subject id so [%t] in the log pattern distinguishes concurrent per-user instances; mirrors car-engine's "Car-<id>".
        executor = executorFactory.createSingleThreadedSchedulingExecutor(logSubjectId.isBlank() ? "Google-Calendar" : "Google-Calendar-" + logSubjectId);
        HttpRequestInitializer requestInitializer = request -> {
            request.setConnectTimeout(timeoutMillis).setReadTimeout(timeoutMillis);
            if (accessToken != null) {
                request.getHeaders().setAuthorization("Bearer " + accessToken);
            }
        };
        calendarApi = new com.google.api.services.calendar.Calendar.Builder(createHttpTransport(), GsonFactory.getDefaultInstance(), requestInitializer)
                .setApplicationName(APPLICATION_NAME)
                .build();
        // Cache the latest access token for the request initializer and re-publish the token manager's state as our auth state (hiding the raw token). The
        // callback fires on the token manager's executor, so marshal onto our executor to keep accessToken single-threaded with the API calls that read it.
        tokenSubscription = tokenManager.subscribeToAccessTokenState(state -> executor.execute(() -> {
            if (state instanceof AuthState.Success(String authInfo)) {
                accessToken = authInfo;
                authState.accept(AUTHENTICATED);
            } else {
                authState.accept(state);
            }
        }));
        // A freshly-supplied auth code means the user has just logged in; exchange it. Otherwise the token manager refreshes the persisted token on its own.
        authCode.ifPresent(code -> tokenManager.onNewAuthCode(code, redirectUri, codeVerifier));
    }

    @Override
    protected void doStop() {
        closeSafelyIfNotNull(logger, tokenSubscription, executor);
        tokenSubscription = null;
    }

    /// The HTTP transport the Calendar client is built on. Overridden in tests to supply a mock transport that returns canned API responses.
    @VisibleForTesting
    HttpTransport createHttpTransport() {
        return new NetHttpTransport();
    }

    @Override
    public Closeable subscribeToAuthState(Consumer<AuthState> consumer) {
        return authState.subscribe(consumer);
    }

    @Override
    public CompletableFuture<List<Calendar>> retrieveCalendars() {
        return whenStartedAndNotLifecycling(() -> executor.submit(() -> {
            syncCalendarList();
            return ImmutableList.copyOf(calendarsById.values());
        }));
    }

    /// Refreshes [#calendarsById] from the Calendar API. The first call (and any call after the sync token has expired) does a full list; later calls send the
    /// stored sync token and apply only the changed and removed entries, so each refresh transfers just the delta. On a `410` expiry the token is dropped and a
    /// single full resync is performed. Runs on [#executor].
    private void syncCalendarList() {
        boolean retriedAfterExpiry = false;
        while (true) {
            if (calendarListSyncToken == null) {
                calendarsById.clear();
            }
            try {
                String pageToken = null;
                String nextSyncToken = null;
                do {
                    CalendarList response = calendarApi.calendarList().list()
                                                       .setShowDeleted(true)
                                                       .setSyncToken(calendarListSyncToken)
                                                       .setPageToken(pageToken)
                                                       .execute();
                    List<CalendarListEntry> items = response.getItems();
                    if (items != null) {
                        for (CalendarListEntry entry : items) {
                            if (Boolean.TRUE.equals(entry.getDeleted())) {
                                calendarsById.remove(entry.getId());
                            } else {
                                calendarsById.put(entry.getId(), new GoogleCalendar(calendarApi, entry.getId(), calendarName(entry), executor));
                            }
                        }
                    }
                    pageToken = response.getNextPageToken();
                    if (response.getNextSyncToken() != null) {
                        nextSyncToken = response.getNextSyncToken();
                    }
                } while (pageToken != null);
                if (nextSyncToken != null) {
                    calendarListSyncToken = nextSyncToken;
                }
                return;
            } catch (GoogleJsonResponseException e) {
                if (e.getStatusCode() == 410 && calendarListSyncToken != null && !retriedAfterExpiry) {
                    logger.info("Google calendar list sync token expired; performing a full resync");
                    calendarListSyncToken = null;
                    retriedAfterExpiry = true;
                } else {
                    if (isPermanentAuthError(e)) {
                        // The credential is no longer accepted by the API — revoked access, a deleted client project, or the API not being enabled — even
                        // though the token itself may still refresh. Invalidate it (escalating to a permanent auth failure) rather than retrying this call
                        // indefinitely against a credential that will never work.
                        tokenManager.invalidate("Google Calendar API rejected the credential (HTTP " + e.getStatusCode() + ')');
                    }
                    throw new RuntimeException("Failed to sync Google calendar list", e);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to sync Google calendar list", e);
            }
        }
    }

    /// Whether a Calendar API error means the credential is permanently rejected and the user must re-authenticate: a `401`, or a `403` that is not a transient
    /// rate/quota limit. A deleted client project, revoked access, or the API not being enabled all surface as a non-rate-limit `403`, whereas a rate-limit
    /// `403` is transient and must not trigger re-authentication.
    private static boolean isPermanentAuthError(GoogleJsonResponseException e) {
        int statusCode = e.getStatusCode();
        if (statusCode == 401) {
            return true;
        }
        if (statusCode != 403) {
            return false;
        }
        // Treat a 403 without a recognised reason as permanent: better to prompt re-auth than to retry a genuinely-broken credential forever.
        return errorReason(e).map(reason -> !TRANSIENT_403_REASONS.contains(reason)).orElse(true);
    }

    private static Optional<String> errorReason(GoogleJsonResponseException e) {
        return Optional.ofNullable(e.getDetails())
                       .map(GoogleJsonError::getErrors)
                       .filter(errors -> !errors.isEmpty())
                       .map(errors -> errors.getFirst().getReason());
    }

    private static String calendarName(CalendarListEntry entry) {
        String override = entry.getSummaryOverride();
        if (override != null) {
            return override;
        }
        String summary = entry.getSummary();
        return summary != null ? summary : entry.getId();
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
