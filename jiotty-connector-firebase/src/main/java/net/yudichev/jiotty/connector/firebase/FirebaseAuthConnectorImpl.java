package net.yudichev.jiotty.connector.firebase;

import com.google.api.core.ApiFuture;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import com.google.firebase.auth.UserInfo;
import com.google.firebase.auth.UserRecord;
import com.google.inject.BindingAnnotation;
import jakarta.inject.Inject;
import net.yudichev.jiotty.common.inject.BaseLifecycleComponent;
import net.yudichev.jiotty.common.lang.Closeable;
import net.yudichev.jiotty.common.lang.Either;
import net.yudichev.jiotty.user.persistence.UserIdentity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static net.yudichev.jiotty.common.lang.MoreThrowables.getAsUnchecked;

public final class FirebaseAuthConnectorImpl extends BaseLifecycleComponent implements FirebaseAuthConnector {
    private static final Logger logger = LogManager.getLogger(FirebaseAuthConnectorImpl.class);
    /// Firebase Auth provider ID used for email/password sign-in.
    ///
    /// The Admin SDK does not expose a constant for this value.
    private static final String PASSWORD_PROVIDER_ID = "password";
    /// Firebase Auth provider ID used for phone sign-in.
    ///
    /// The Admin SDK does not expose a constant for this value.
    private static final String PHONE_PROVIDER_ID = "phone";

    private final Optional<GoogleCredentials> credentials;
    private final Optional<String> projectId;
    private final int httpTimeoutMillis;

    private FirebaseApp firebaseApp;
    private FirebaseAuth firebaseAuth;

    @Inject
    public FirebaseAuthConnectorImpl(@Dependency Optional<GoogleCredentials> credentials,
                                     @Dependency Optional<String> projectId,
                                     @Dependency Duration httpTimeout) {
        this.credentials = checkNotNull(credentials, "credentials");
        this.projectId = checkNotNull(projectId, "projectId");
        checkNotNull(httpTimeout, "httpTimeout");
        checkArgument(httpTimeout.isPositive(), "httpTimeout must be positive and at least 1 ms, was %s", httpTimeout);
        httpTimeoutMillis = Math.toIntExact(httpTimeout.toMillis());
    }

    FirebaseAuthConnectorImpl(Optional<GoogleCredentials> credentials,
                              Optional<String> projectId,
                              Duration httpTimeout,
                              FirebaseApp firebaseApp,
                              FirebaseAuth firebaseAuth) {
        this(credentials, projectId, httpTimeout);
        this.firebaseApp = checkNotNull(firebaseApp, "firebaseApp");
        this.firebaseAuth = checkNotNull(firebaseAuth, "firebaseAuth");
    }

    @Override
    protected void doStart() {
        if (firebaseApp != null) {
            assert firebaseAuth != null;
            return;
        }
        var optionsBuilder = FirebaseOptions.builder().setCredentials(credentials.orElseGet(() -> getAsUnchecked(GoogleCredentials::getApplicationDefault)));
        projectId.ifPresent(optionsBuilder::setProjectId);
        optionsBuilder.setConnectTimeout(httpTimeoutMillis);
        optionsBuilder.setReadTimeout(httpTimeoutMillis);
        optionsBuilder.setWriteTimeout(httpTimeoutMillis);
        firebaseApp = FirebaseApp.initializeApp(optionsBuilder.build(), getClass().getName() + '@' + Integer.toHexString(System.identityHashCode(this)));
        firebaseAuth = FirebaseAuth.getInstance(firebaseApp);
    }

    @Override
    protected void doStop() {
        Closeable.closeSafelyIfNotNull(logger, firebaseApp == null ? null : firebaseApp::delete);
    }

    @Override
    public CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> verifyUserToken(String idToken) {
        checkNotNull(idToken, "idToken");
        checkArgument(!idToken.isBlank(), "idToken must not be blank");
        return whenStartedAndNotLifecycling(() -> verifyDecodedToken(idToken)
                .thenCompose(decodedTokenOrFailure -> decodedTokenOrFailure.map(this::loadUser,
                                                                                failure -> CompletableFuture.completedFuture(Either.right(failure)))));
    }

    private CompletableFuture<Either<FirebaseToken, VerificationFailure>> verifyDecodedToken(String idToken) {
        return toCompletableFuture(firebaseAuth.verifyIdTokenAsync(idToken, false))
                .handle((decodedToken, throwable) -> throwable == null
                                                     ? CompletableFuture.completedFuture(Either.<FirebaseToken, VerificationFailure>left(decodedToken))
                                                     : mapVerificationFailure(throwable))
                .thenCompose(result -> result);
    }

    private CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> loadUser(FirebaseToken decodedToken) {
        return toCompletableFuture(firebaseAuth.getUserAsync(decodedToken.getUid()))
                .handle((userRecord, throwable) -> throwable == null
                                                   ? CompletableFuture.completedFuture(mapVerifiedUserToken(decodedToken, userRecord))
                                                   : mapUserLookupFailure(decodedToken.getUid(), throwable))
                .thenCompose(result -> result);
    }

    private static Either<VerifiedUserToken, VerificationFailure> mapVerifiedUserToken(FirebaseToken decodedToken, UserRecord userRecord) {
        if (userRecord.isDisabled()) {
            return Either.right(new DisabledUser(decodedToken.getUid(), "Firebase user is disabled"));
        }
        Instant issuedAt = Instant.ofEpochSecond(tokenInstantClaim(decodedToken, "iat"));
        Instant expiresAt = Instant.ofEpochSecond(tokenInstantClaim(decodedToken, "exp"));
        Instant tokensValidAfter = Instant.ofEpochMilli(userRecord.getTokensValidAfterTimestamp());
        if (issuedAt.isBefore(tokensValidAfter)) {
            return Either.right(new InvalidToken("Firebase token has been revoked"));
        }
        return Either.left(new VerifiedUserToken(decodedToken.getUid(),
                                                 new FirebaseUserProfile(Optional.ofNullable(nonBlankOrNull(userRecord.getEmail())),
                                                                         Optional.ofNullable(nonBlankOrNull(userRecord.getDisplayName()))),
                                                 createLinkedIdentities(userRecord.getProviderData()),
                                                 issuedAt,
                                                 expiresAt));
    }

    private static List<UserIdentity> createLinkedIdentities(UserInfo[] providerData) {
        var linkedIdentities = new ArrayList<UserIdentity>(providerData.length);
        var providers = new HashSet<String>(providerData.length);
        for (UserInfo userInfo : providerData) {
            if (keepLinkedIdentity(userInfo)) {
                String providerId = userInfo.getProviderId();
                checkState(providers.add(providerId),
                           "Unexpected firebase user record data: duplicate provider identities for provider %s",
                           providerId);
                linkedIdentities.add(new UserIdentity(providerId, userInfo.getUid()));
            }
        }
        return linkedIdentities;
    }

    private static boolean keepLinkedIdentity(UserInfo userInfo) {
        String providerId = userInfo.getProviderId();
        String providerUserId = userInfo.getUid();
        if (providerId == null || providerUserId == null || providerId.isBlank() || providerUserId.isBlank()) {
            return false;
        }
        return !PASSWORD_PROVIDER_ID.equals(providerId) && !PHONE_PROVIDER_ID.equals(providerId);
    }

    private static CompletableFuture<Either<FirebaseToken, VerificationFailure>> mapVerificationFailure(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof FirebaseAuthException firebaseAuthException) {
            AuthErrorCode authErrorCode = firebaseAuthException.getAuthErrorCode();
            return switch (authErrorCode) {
                case INVALID_ID_TOKEN, EXPIRED_ID_TOKEN, REVOKED_ID_TOKEN, USER_NOT_FOUND -> CompletableFuture.completedFuture(
                        Either.right(new InvalidToken("Firebase token is invalid: " + describeFirebaseAuthException(authErrorCode, firebaseAuthException))));
                case null -> failedTransientFailure(
                        "Firebase token verification failed: " + describeFirebaseAuthException(authErrorCode, firebaseAuthException),
                        firebaseAuthException);
                case CERTIFICATE_FETCH_FAILED,
                     CONFIGURATION_NOT_FOUND,
                     EMAIL_ALREADY_EXISTS,
                     EMAIL_NOT_FOUND,
                     EXPIRED_SESSION_COOKIE,
                     INVALID_DYNAMIC_LINK_DOMAIN,
                     INVALID_HOSTING_LINK_DOMAIN,
                     INVALID_SESSION_COOKIE,
                     PHONE_NUMBER_ALREADY_EXISTS,
                     REVOKED_SESSION_COOKIE,
                     TENANT_ID_MISMATCH,
                     TENANT_NOT_FOUND,
                     UID_ALREADY_EXISTS,
                     UNAUTHORIZED_CONTINUE_URL,
                     USER_DISABLED -> failedTransientFailure(
                        "Firebase token verification failed: " + describeFirebaseAuthException(authErrorCode, firebaseAuthException),
                        firebaseAuthException);
            };
        }
        return failedTransientFailure("Firebase token verification failed: " + cause.getMessage(), cause);
    }

    private static CompletableFuture<Either<VerifiedUserToken, VerificationFailure>> mapUserLookupFailure(String firebaseUid, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof FirebaseAuthException firebaseAuthException) {
            AuthErrorCode authErrorCode = firebaseAuthException.getAuthErrorCode();
            return switch (authErrorCode) {
                case USER_NOT_FOUND, INVALID_ID_TOKEN, EXPIRED_ID_TOKEN, REVOKED_ID_TOKEN -> CompletableFuture.completedFuture(
                        Either.right(new InvalidToken(
                                "Firebase token is invalid: " + describeFirebaseAuthException(authErrorCode, firebaseAuthException))));
                case USER_DISABLED -> CompletableFuture.completedFuture(
                        Either.right(new DisabledUser(
                                firebaseUid,
                                "Firebase user is disabled: " + describeFirebaseAuthException(authErrorCode, firebaseAuthException))));
                case null -> failedTransientFailure(
                        "Firebase user lookup failed: " + describeFirebaseAuthException(authErrorCode, firebaseAuthException),
                        firebaseAuthException);
                case CERTIFICATE_FETCH_FAILED,
                     CONFIGURATION_NOT_FOUND,
                     EMAIL_ALREADY_EXISTS,
                     EMAIL_NOT_FOUND,
                     EXPIRED_SESSION_COOKIE,
                     INVALID_DYNAMIC_LINK_DOMAIN,
                     INVALID_HOSTING_LINK_DOMAIN,
                     INVALID_SESSION_COOKIE,
                     PHONE_NUMBER_ALREADY_EXISTS,
                     REVOKED_SESSION_COOKIE,
                     TENANT_ID_MISMATCH,
                     TENANT_NOT_FOUND,
                     UID_ALREADY_EXISTS,
                     UNAUTHORIZED_CONTINUE_URL -> failedTransientFailure(
                        "Firebase user lookup failed: " + describeFirebaseAuthException(authErrorCode, firebaseAuthException),
                        firebaseAuthException);
            };
        }
        return failedTransientFailure("Firebase user lookup failed: " + cause.getMessage(), cause);
    }

    private static String describeFirebaseAuthException(@Nullable AuthErrorCode authErrorCode, FirebaseAuthException firebaseAuthException) {
        String message = firebaseAuthException.getMessage();
        return authErrorCode == null ? message : "authErrorCode=" + authErrorCode + ": " + message;
    }

    private static long tokenInstantClaim(FirebaseToken decodedToken, String claimName) {
        Object value = decodedToken.getClaims().get(claimName);
        checkArgument(value instanceof Number, "Firebase token claim %s is missing or not numeric", claimName);
        return ((Number) value).longValue();
    }

    private static @Nullable String nonBlankOrNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable result = throwable;
        while ((result instanceof CompletionException || result instanceof ExecutionException) && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static <T> CompletableFuture<T> failedTransientFailure(String message, Throwable cause) {
        return CompletableFuture.failedFuture(new RuntimeException(message, cause));
    }

    private static <T> CompletableFuture<T> toCompletableFuture(ApiFuture<T> apiFuture) {
        var result = new CompletableFuture<T>();
        apiFuture.addListener(() -> {
            try {
                result.complete(apiFuture.get());
            } catch (@SuppressWarnings("OverlyBroadCatchBlock") Exception e) {
                result.completeExceptionally(e);
            }
        }, Runnable::run);
        return result;
    }

    @BindingAnnotation
    @Target({FIELD, PARAMETER, METHOD})
    @Retention(RUNTIME)
    @interface Dependency {
    }
}
